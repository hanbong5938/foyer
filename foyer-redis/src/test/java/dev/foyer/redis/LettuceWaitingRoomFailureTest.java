package dev.foyer.redis;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foyer.core.PollBackoff;
import dev.foyer.core.WaitingRoomUnavailableException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Injected command failures; no Redis server needed. */
class LettuceWaitingRoomFailureTest {

    /** Scripted command double: each call to scriptLoad/evalsha is answered by the given behaviour. */
    static final class FakeCommands {
        final List<String> loads = new CopyOnWriteArrayList<>();
        final List<String> evalShas = new CopyOnWriteArrayList<>();
        Function<Integer, RedisFuture<?>> onLoad = n -> completed("sha-" + n);
        Function<Integer, RedisFuture<?>> onEval = n -> completed(1L);

        @SuppressWarnings("unchecked")
        RedisScriptingAsyncCommands<String, String> proxy() {
            return (RedisScriptingAsyncCommands<String, String>) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {RedisScriptingAsyncCommands.class},
                    (self, method, args) -> switch (method.getName()) {
                        case "scriptLoad" -> {
                            loads.add((String) args[0]);
                            yield onLoad.apply(loads.size());
                        }
                        case "evalsha" -> {
                            evalShas.add((String) args[0]);
                            yield onEval.apply(evalShas.size());
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> AsyncCommand<String, String, T> pending() {
        return new AsyncCommand(new Command(CommandType.EVALSHA, new StatusOutput<>(StringCodec.UTF8)));
    }

    static <T> RedisFuture<T> completed(T value) {
        AsyncCommand<String, String, T> future = pending();
        future.complete(value);
        return future;
    }

    static <T> RedisFuture<T> failed(Throwable error) {
        AsyncCommand<String, String, T> future = pending();
        future.completeExceptionally(error);
        return future;
    }

    static LettuceWaitingRoom room(Supplier<CompletionStage<RedisScriptingAsyncCommands<String, String>>> redis) {
        return new LettuceWaitingRoom(redis, Clock.systemUTC(), PollBackoff.defaults(), "t");
    }

    static LettuceWaitingRoom room(FakeCommands commands) {
        RedisScriptingAsyncCommands<String, String> proxy = commands.proxy();
        return room(() -> CompletableFuture.completedStage(proxy));
    }

    static void assertUnavailable(CompletionStage<?> stage) {
        assertThatThrownBy(() -> stage.toCompletableFuture().get(5, SECONDS))
                .hasCauseInstanceOf(WaitingRoomUnavailableException.class)
                .cause()
                .hasMessage("redis call failed");
    }

    static void completesNormally(CompletionStage<?> stage) throws Exception {
        stage.toCompletableFuture().get(5, SECONDS);
    }

    @Test
    void supplierThrowingSynchronouslyIsUnavailable() {
        LettuceWaitingRoom room = room(() -> {
            throw new IllegalStateException("boom");
        });
        assertUnavailable(room.leave("r", "v"));
    }

    @Test
    void supplierReturningFailedStageIsUnavailable() {
        LettuceWaitingRoom room = room(() -> CompletableFuture.failedStage(new IllegalStateException("boom")));
        assertUnavailable(room.leave("r", "v"));
    }

    @Test
    void scriptLoadThrowingSynchronouslyIsUnavailable() {
        FakeCommands commands = new FakeCommands();
        commands.onLoad = n -> {
            throw new RedisException("connection closed");
        };
        assertUnavailable(room(commands).leave("r", "v"));
        assertThat(commands.evalShas).isEmpty();
    }

    @Test
    void cachedEvalshaThrowingSynchronouslyIsUnavailable() throws Exception {
        FakeCommands commands = new FakeCommands();
        commands.onEval = n -> {
            if (n == 1) return completed(1L);
            throw new RedisException("connection closed");
        };
        LettuceWaitingRoom room = room(commands);
        completesNormally(room.leave("r", "v"));

        assertUnavailable(room.leave("r", "v"));
        assertThat(commands.loads).hasSize(1);
        assertThat(commands.evalShas).containsExactly("sha-1", "sha-1");
    }

    @Test
    void failedReloadAfterNoScriptIsUnavailableWithoutFurtherRetry() throws Exception {
        FakeCommands commands = new FakeCommands();
        commands.onLoad = n -> n == 1 ? completed("sha-1") : failed(new RedisException("down"));
        commands.onEval = n -> n == 1 ? completed(1L) : failed(new RedisNoScriptException("NOSCRIPT No matching script"));
        LettuceWaitingRoom room = room(commands);
        completesNormally(room.leave("r", "v"));

        assertUnavailable(room.leave("r", "v"));
        assertThat(commands.loads).hasSize(2);
        assertThat(commands.evalShas).hasSize(2);
    }

    @Test
    void noScriptReloadsAndEvaluatesWithTheNewSha() throws Exception {
        FakeCommands commands = new FakeCommands();
        commands.onEval = n -> n == 2 ? failed(new RedisNoScriptException("NOSCRIPT No matching script")) : completed(1L);
        LettuceWaitingRoom room = room(commands);
        completesNormally(room.leave("r", "v"));

        completesNormally(room.leave("r", "v"));
        assertThat(commands.loads).hasSize(2);
        assertThat(commands.evalShas).containsExactly("sha-1", "sha-1", "sha-2");

        completesNormally(room.leave("r", "v"));
        assertThat(commands.loads).as("reloaded sha is cached").hasSize(2);
        assertThat(commands.evalShas).last().isEqualTo("sha-2");
    }

    @Test
    void otherCommandFailureIsUnavailableWithoutReload() {
        FakeCommands commands = new FakeCommands();
        commands.onEval = n -> failed(new RedisException("ERR something else"));
        assertUnavailable(room(commands).leave("r", "v"));
        assertThat(commands.loads).hasSize(1);
        assertThat(commands.evalShas).hasSize(1);
    }

    @Test
    void otherFailureOnCachedShaIsNotRetried() throws Exception {
        FakeCommands commands = new FakeCommands();
        commands.onEval = n -> n == 1 ? completed(1L) : failed(new RedisException("ERR something else"));
        LettuceWaitingRoom room = room(commands);
        completesNormally(room.leave("r", "v"));

        assertUnavailable(room.leave("r", "v"));
        assertThat(commands.loads).hasSize(1);
        assertThat(commands.evalShas).hasSize(2);
    }
}
