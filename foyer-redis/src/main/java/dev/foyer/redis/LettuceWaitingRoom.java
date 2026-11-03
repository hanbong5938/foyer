package dev.foyer.redis;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.PollBackoff;
import dev.foyer.core.QueueStatus;
import dev.foyer.core.RoomStats;
import dev.foyer.core.WaitingRoom;
import dev.foyer.core.WaitingRoomUnavailableException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * {@link WaitingRoom} on Redis sorted sets, driven by a single Lua script so enter/poll/admit is atomic per room.
 * Works with standalone and cluster connections (keys are hash-tagged by room).
 */
public final class LettuceWaitingRoom implements WaitingRoom {

    private static final String ADMIT = loadResource("admit.lua");
    private static final String LEAVE = "redis.call('ZREM', KEYS[1], ARGV[1]) redis.call('ZREM', KEYS[2], ARGV[1]) return 1";
    private static final String STATS = "redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[1]) "
            + "local waiting "
            + "if ARGV[2] == '' then waiting = redis.call('ZCARD', KEYS[1]) "
            + "else waiting = redis.call('ZCOUNT', KEYS[2], ARGV[2], '+inf') end "
            + "return { waiting, redis.call('ZCARD', KEYS[3]) }";

    private final Supplier<CompletionStage<RedisScriptingAsyncCommands<String, String>>> redis;
    private final Clock clock;
    private final PollBackoff backoff;
    private final String keyPrefix;
    private final Script admit;
    private final Script leave;
    private final Script stats;

    public LettuceWaitingRoom(RedisScriptingAsyncCommands<String, String> redis) {
        this(redis, Clock.systemUTC(), PollBackoff.defaults(), "foyer");
    }

    public LettuceWaitingRoom(
            RedisScriptingAsyncCommands<String, String> redis, Clock clock, PollBackoff backoff, String keyPrefix) {
        this(constant(redis), clock, backoff, keyPrefix);
    }

    /**
     * @param redis yields the connection for each call, so it can be established lazily and re-established after a
     *              failure (see {@link LazyLettuceConnection}); a failed stage is reported as unavailable
     */
    public LettuceWaitingRoom(
            Supplier<CompletionStage<RedisScriptingAsyncCommands<String, String>>> redis,
            Clock clock, PollBackoff backoff, String keyPrefix) {
        this.redis = Objects.requireNonNull(redis);
        this.clock = Objects.requireNonNull(clock);
        this.backoff = Objects.requireNonNull(backoff);
        this.keyPrefix = Objects.requireNonNull(keyPrefix);
        this.admit = new Script(ADMIT);
        this.leave = new Script(LEAVE);
        this.stats = new Script(STATS);
    }

    private static Supplier<CompletionStage<RedisScriptingAsyncCommands<String, String>>> constant(
            RedisScriptingAsyncCommands<String, String> commands) {
        Objects.requireNonNull(commands);
        CompletionStage<RedisScriptingAsyncCommands<String, String>> ready = CompletableFuture.completedStage(commands);
        return () -> ready;
    }

    @Override
    public CompletionStage<QueueStatus> enter(String room, String visitorId, AdmissionPolicy policy) {
        return admit(room, visitorId, policy, true);
    }

    @Override
    public CompletionStage<QueueStatus> poll(String room, String visitorId, AdmissionPolicy policy) {
        return admit(room, visitorId, policy, false);
    }

    @Override
    public CompletionStage<Void> leave(String room, String visitorId) {
        return leave.run(ScriptOutputType.INTEGER, new String[] {key(room, "queue"), key(room, "seen")}, visitorId)
                .thenApply(ignored -> (Void) null);
    }

    @Override
    public CompletionStage<RoomStats> stats(String room, AdmissionPolicy policy) {
        long now = clock.millis();
        long queueTtlMillis = policy.queueTtl().toMillis();
        String lowerBound = queueTtlMillis > 0 ? "(" + (now - queueTtlMillis) : "";
        return stats.<List<Object>>run(
                        ScriptOutputType.MULTI,
                        new String[] {key(room, "queue"), key(room, "seen"), key(room, "active")},
                        Long.toString(now), lowerBound)
                .thenApply(reply -> new RoomStats((Long) reply.get(0), (Long) reply.get(1)));
    }

    private CompletionStage<QueueStatus> admit(String room, String visitorId, AdmissionPolicy policy, boolean enroll) {
        String[] keys = {key(room, "queue"), key(room, "seen"), key(room, "active"), key(room, "meta")};
        String[] args = {
            visitorId,
            Long.toString(clock.millis()),
            enroll ? "1" : "0",
            Integer.toString(policy.rate()),
            Long.toString(policy.interval().toMillis()),
            Integer.toString(policy.burst()),
            Integer.toString(policy.maxActive()),
            Long.toString(policy.tokenTtl().toMillis()),
            Long.toString(policy.queueTtl().toMillis()),
        };
        return admit.<List<Object>>run(ScriptOutputType.MULTI, keys, args).thenApply(reply -> toStatus(reply, policy));
    }

    private QueueStatus toStatus(List<Object> reply, AdmissionPolicy policy) {
        String state = (String) reply.get(0);
        long position = (Long) reply.get(1);
        long queueSize = (Long) reply.get(2);
        long admittedUntil = (Long) reply.get(3);
        return switch (state) {
            case "ADMITTED" -> QueueStatus.admitted(queueSize, Instant.ofEpochMilli(admittedUntil));
            case "WAITING" -> QueueStatus.waiting(position, queueSize, backoff.retryAfter(position, policy));
            case "NOT_IN_QUEUE" -> QueueStatus.notInQueue(queueSize);
            default -> throw new IllegalStateException("unexpected reply from admit script: " + reply);
        };
    }

    private String key(String room, String suffix) {
        return keyPrefix + ":{" + room + "}:" + suffix;
    }

    /** EVALSHA with transparent SCRIPT LOAD on NOSCRIPT (first call, or after a Redis restart / failover). */
    private final class Script {
        private final String body;
        private volatile String sha;

        Script(String body) {
            this.body = body;
        }

        /**
         * Every failure (connection, command, or a synchronous throw from the supplier or command interface) ends
         * in a stage completed exceptionally with {@link WaitingRoomUnavailableException}; nothing stays pending.
         */
        <T> CompletionStage<T> run(ScriptOutputType type, String[] keys, String... args) {
            CompletionStage<RedisScriptingAsyncCommands<String, String>> connection;
            try {
                connection = Objects.requireNonNull(redis.get(), "redis connection supplier returned null");
            } catch (RuntimeException e) {
                return CompletableFuture.failedStage(unavailable(e));
            }
            return connection
                    .<T>thenCompose(commands -> attempt(commands, type, keys, args, true))
                    .exceptionallyCompose(error -> CompletableFuture.<T>failedStage(unavailable(error)));
        }

        private <T> CompletionStage<T> attempt(
                RedisScriptingAsyncCommands<String, String> commands, ScriptOutputType type, String[] keys,
                String[] args, boolean retryOnNoScript) {
            String current = sha;
            if (current == null) {
                return load(commands).thenCompose(loaded -> evaluate(commands, loaded, type, keys, args, false));
            }
            return evaluate(commands, current, type, keys, args, retryOnNoScript);
        }

        private CompletionStage<String> load(RedisScriptingAsyncCommands<String, String> commands) {
            CompletionStage<String> loading;
            try {
                loading = Objects.requireNonNull(commands.scriptLoad(body), "scriptLoad returned null");
            } catch (RuntimeException e) {
                return CompletableFuture.failedStage(e);
            }
            return loading.thenApply(loaded -> {
                sha = loaded;
                return loaded;
            });
        }

        private <T> CompletionStage<T> evaluate(
                RedisScriptingAsyncCommands<String, String> commands, String digest, ScriptOutputType type,
                String[] keys, String[] args, boolean retryOnNoScript) {
            CompletionStage<T> evaluation;
            try {
                evaluation = Objects.requireNonNull(commands.<T>evalsha(digest, type, keys, args), "evalsha returned null");
            } catch (RuntimeException e) {
                return CompletableFuture.failedStage(e);
            }
            if (!retryOnNoScript) return evaluation;
            return evaluation.exceptionallyCompose(error -> {
                if (!(unwrap(error) instanceof RedisNoScriptException)) return CompletableFuture.failedStage(error);
                sha = null;
                return attempt(commands, type, keys, args, false);
            });
        }

        private Throwable unwrap(Throwable error) {
            Throwable t = error;
            while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
                t = t.getCause();
            }
            return t;
        }

        private WaitingRoomUnavailableException unavailable(Throwable error) {
            Throwable cause = unwrap(error);
            if (cause instanceof WaitingRoomUnavailableException unavailable) return unavailable;
            return new WaitingRoomUnavailableException("redis call failed", cause);
        }
    }

    private static String loadResource(String name) {
        try (InputStream in = LettuceWaitingRoom.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
