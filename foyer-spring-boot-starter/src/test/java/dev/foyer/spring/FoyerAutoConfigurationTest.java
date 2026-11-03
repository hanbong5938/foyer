package dev.foyer.spring;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foyer.core.AdmissionToken;
import dev.foyer.core.AdmissionTokenCodec;
import dev.foyer.core.HmacTokenCodec;
import dev.foyer.core.WaitingRoom;
import dev.foyer.core.WaitingRoomUnavailableException;
import dev.foyer.redis.LazyLettuceConnection;
import dev.foyer.redis.LettuceWaitingRoom;
import dev.foyer.spring.gate.AdmissionGate;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Bean selection of the Redis-backed {@link WaitingRoom} and property binding; no Redis server needed. */
class FoyerAutoConfigurationTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoyerAutoConfiguration.class))
            .withPropertyValues("foyer.secret=" + SECRET, "foyer.rooms.demo.rate=5");

    /** Every connect attempt is counted and fails immediately; the context owns and shuts the client down. */
    static final class FakeClient extends RedisClient {
        final AtomicInteger connects = new AtomicInteger();

        @Override
        public <K, V> ConnectionFuture<StatefulRedisConnection<K, V>> connectAsync(RedisCodec<K, V> codec, RedisURI uri) {
            connects.incrementAndGet();
            CompletableFuture<StatefulRedisConnection<K, V>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RedisConnectionException("refused: " + uri));
            return ConnectionFuture.from(new InetSocketAddress("localhost", 1), failed);
        }
    }

    /** Connection double: {@code async()} yields a command proxy answering scriptLoad/evalsha, everything else is logged. */
    static final class FakeConnection {
        final List<String> commands = new CopyOnWriteArrayList<>();
        final AtomicInteger asyncCalls = new AtomicInteger();

        @SuppressWarnings({"unchecked", "rawtypes"})
        static <T> RedisFuture<T> completed(T value) {
            AsyncCommand<String, String, T> future =
                    new AsyncCommand(new Command(CommandType.EVALSHA, new StatusOutput<>(StringCodec.UTF8)));
            future.complete(value);
            return future;
        }

        @SuppressWarnings("unchecked")
        RedisAsyncCommands<String, String> commands() {
            return (RedisAsyncCommands<String, String>) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {RedisAsyncCommands.class},
                    (self, method, args) -> {
                        commands.add(method.getName());
                        return switch (method.getName()) {
                            case "scriptLoad" -> completed("sha-" + commands.size());
                            case "evalsha" -> completed(1L);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        @SuppressWarnings("unchecked")
        <V> StatefulRedisConnection<String, V> proxy() {
            return (StatefulRedisConnection<String, V>) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {StatefulRedisConnection.class},
                    (self, method, args) -> switch (method.getName()) {
                        case "async" -> {
                            asyncCalls.incrementAndGet();
                            yield commands();
                        }
                        case "close" -> null;
                        case "closeAsync" -> CompletableFuture.completedFuture(null);
                        case "hashCode" -> System.identityHashCode(self);
                        case "equals" -> self == args[0];
                        case "toString" -> "FakeConnection";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ClientOnly {
        @Bean
        FakeClient redisClient() {
            return new FakeClient();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BinaryConnection {
        static final FakeConnection CONNECTION = new FakeConnection();

        @Bean
        FakeClient redisClient() {
            return new FakeClient();
        }

        @Bean
        StatefulRedisConnection<String, byte[]> binaryRedisConnection() {
            return CONNECTION.proxy();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StringConnection {
        static final FakeConnection CONNECTION = new FakeConnection();

        @Bean
        FakeClient redisClient() {
            return new FakeClient();
        }

        @Bean
        StatefulRedisConnection<String, String> stringRedisConnection() {
            return CONNECTION.proxy();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InMemory {
        @Bean
        RecordingWaitingRoom waitingRoom() {
            return new RecordingWaitingRoom();
        }
    }

    static final Instant FUTURE = Instant.parse("2031-06-01T00:00:00Z");

    public record ClockConsumer(Clock clock) {}

    /** Application-owned Clock plus a consumer that names its parameter differently from Foyer's bean. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationClock {
        static final Clock CLOCK = Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Bean
        Clock applicationClock() {
            return CLOCK;
        }

        @Bean
        ClockConsumer clockConsumer(Clock appClock) {
            return new ClockConsumer(appClock);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OptionalClockConsumer {
        final ObjectProvider<Clock> clocks;

        OptionalClockConsumer(ObjectProvider<Clock> clocks) {
            this.clocks = clocks;
        }
    }

    /** Unrelated primary application Clock next to a named Foyer override. */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryAndNamed {
        @Bean
        @Primary
        Clock applicationClock() {
            return ApplicationClock.CLOCK;
        }

        @Bean
        Clock foyerClock() {
            return Clock.fixed(FUTURE, ZoneOffset.UTC);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NamedOnly {
        @Bean
        Clock foyerClock() {
            return Clock.fixed(FUTURE, ZoneOffset.UTC);
        }
    }

    /** Issue a token through the service and read its {@code iat} back with an independent, real-time codec. */
    static Instant issuedAt(FoyerService service) throws Exception {
        FoyerService.Outcome outcome = service.enter("demo", "v1").toCompletableFuture().get(5, SECONDS);
        assertThat(outcome.token()).isNotNull();
        return new HmacTokenCodec(SECRET, Clock.systemUTC()).decode(outcome.token()).orElseThrow().issuedAt();
    }

    /**
     * Proves the named {@code foyerClock} drives both issuance and the autoconfigured codec's expiry check. The
     * in-memory store stamps {@code admittedUntil} from real time, so the service-issued token is live under the
     * system clock yet already expired at {@link #FUTURE}; boundary tokens pin the codec's "now" to exactly FUTURE.
     */
    static void assertFoyerClockIsFuture(FoyerService service, AdmissionTokenCodec codec) throws Exception {
        FoyerService.Outcome outcome = service.enter("demo", "v1").toCompletableFuture().get(5, SECONDS);
        assertThat(outcome.token()).isNotNull();
        Instant admittedUntil = outcome.status().admittedUntil();
        assertThat(admittedUntil).isBefore(FUTURE);

        AdmissionToken decoded =
                new HmacTokenCodec(SECRET, Clock.systemUTC()).decode(outcome.token()).orElseThrow();
        assertThat(decoded.room()).isEqualTo("demo");
        assertThat(decoded.visitorId()).isEqualTo("v1");
        assertThat(decoded.issuedAt()).isEqualTo(FUTURE);
        assertThat(decoded.expiresAt()).isEqualTo(admittedUntil);

        assertThat(codec.decode(outcome.token())).as("expired at the named foyerClock").isEmpty();

        String atFuture = codec.encode(new AdmissionToken("demo", "v1", FUTURE, FUTURE));
        String justAfter = codec.encode(new AdmissionToken("demo", "v1", FUTURE, FUTURE.plusMillis(1)));
        assertThat(codec.decode(atFuture)).as("expiry equal to foyerClock is rejected").isEmpty();
        assertThat(codec.decode(justAfter)).as("expiry just after foyerClock is accepted")
                .hasValueSatisfying(token -> assertThat(token.expiresAt()).isEqualTo(FUTURE.plusMillis(1)));
    }

    static void assertUnavailable(CompletionStage<?> stage) {
        assertThatThrownBy(() -> stage.toCompletableFuture().get(5, SECONDS))
                .hasCauseInstanceOf(WaitingRoomUnavailableException.class);
    }

    @Test
    void withoutAnyConnectionTheLazyFallbackConnectsOnFirstUseOnly() {
        runner.withUserConfiguration(ClientOnly.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(LazyLettuceConnection.class);
            FakeClient client = context.getBean(FakeClient.class);
            assertThat(client.connects).hasValue(0);

            WaitingRoom room = context.getBean(WaitingRoom.class);
            assertThat(room).isInstanceOf(LettuceWaitingRoom.class);
            assertUnavailable(room.leave("demo", "v1"));
            assertThat(client.connects).hasValue(1);
            assertUnavailable(room.leave("demo", "v1"));
            assertThat(client.connects).as("failed connect is retried, not cached").hasValue(2);
        });
    }

    @Test
    void unrelatedGenericConnectionDoesNotSuppressTheLazyFallback() {
        BinaryConnection.CONNECTION.asyncCalls.set(0);
        runner.withUserConfiguration(BinaryConnection.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(LazyLettuceConnection.class);
            FakeClient client = context.getBean(FakeClient.class);

            WaitingRoom room = context.getBean(WaitingRoom.class);
            assertUnavailable(room.leave("demo", "v1"));
            assertThat(client.connects).as("the String/byte[] connection must not be used").hasValue(1);
            assertThat(BinaryConnection.CONNECTION.asyncCalls).hasValue(0);
        });
    }

    @Test
    void stringConnectionIsPreferredAndTheLazyHolderNeverConnects() {
        StringConnection.CONNECTION.asyncCalls.set(0);
        StringConnection.CONNECTION.commands.clear();
        runner.withUserConfiguration(StringConnection.class).run(context -> {
            assertThat(context).hasNotFailed();
            FakeClient client = context.getBean(FakeClient.class);

            WaitingRoom room = context.getBean(WaitingRoom.class);
            room.leave("demo", "v1").toCompletableFuture().get(5, SECONDS);
            assertThat(StringConnection.CONNECTION.asyncCalls).hasValue(1);
            assertThat(StringConnection.CONNECTION.commands).containsExactly("scriptLoad", "evalsha");
            assertThat(client.connects).hasValue(0);
        });
    }

    @Test
    void invalidBasePathFailsBindingByName() {
        for (String bad : List.of("/", "", "foyer", "/foyer/", "/{room}", "/a b")) {
            runner.withUserConfiguration(InMemory.class).withPropertyValues("foyer.base-path=" + bad).run(context -> {
                assertThat(context).as("foyer.base-path=%s", bad).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("foyer.base-path")
                        .hasStackTraceContaining("absolute non-root path");
            });
        }
    }

    @Test
    void nestedBasePathBindsUnchanged() {
        runner.withUserConfiguration(InMemory.class).withPropertyValues("foyer.base-path=/queue/foyer-v2").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FoyerProperties.class).getBasePath()).isEqualTo("/queue/foyer-v2");
            assertThat(context).hasSingleBean(RecordingWaitingRoom.class).doesNotHaveBean(LazyLettuceConnection.class);
        });
    }

    @Test
    void applicationClockIsNotShadowedByFoyersOwnClock() {
        runner.withUserConfiguration(InMemory.class, ApplicationClock.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ClockConsumer.class).clock()).isSameAs(ApplicationClock.CLOCK);
            ObjectProvider<Clock> unqualified = context.getBeanProvider(Clock.class);
            assertThat(unqualified.getIfAvailable()).isSameAs(ApplicationClock.CLOCK);
            assertThat(issuedAt(context.getBean(FoyerService.class)))
                    .as("Foyer keeps its own system clock")
                    .isAfter(Instant.parse("2020-01-01T00:00:00Z")).isBefore(FUTURE);
        });
    }

    /**
     * Injection points see no Foyer-owned {@code Clock} unless they ask for it by name. (A programmatic
     * {@code getBeanProvider(Clock.class)} with a sole candidate still resolves it: Spring's {@code defaultCandidate}
     * only arbitrates between several candidates there.)
     */
    @Test
    void foyersDefaultClockIsNotAnUnqualifiedInjectionCandidate() {
        runner.withUserConfiguration(InMemory.class, OptionalClockConsumer.class).run(context -> {
            assertThat(context).hasNotFailed().hasBean("foyerClock");
            OptionalClockConsumer consumer = context.getBean(OptionalClockConsumer.class);
            assertThat(consumer.clocks.getIfAvailable()).isNull();
            assertThat(consumer.clocks.getIfUnique()).isNull();
            assertThat(consumer.clocks.stream()).isEmpty();
        });
        runner.withUserConfiguration(InMemory.class, ApplicationClock.class, OptionalClockConsumer.class).run(context -> {
            assertThat(context).hasNotFailed();
            ObjectProvider<Clock> clocks = context.getBean(OptionalClockConsumer.class).clocks;
            assertThat(clocks.getIfUnique()).isSameAs(ApplicationClock.CLOCK);
            assertThat(clocks.stream()).containsExactly(ApplicationClock.CLOCK);
        });
    }

    @Test
    void namedFoyerClockOverrideControlsTokenIssuanceAndExpiry() {
        runner.withUserConfiguration(InMemory.class, NamedOnly.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertFoyerClockIsFuture(context.getBean(FoyerService.class), context.getBean(AdmissionTokenCodec.class));
        });
    }

    @Test
    void unrelatedPrimaryClockDoesNotControlFoyer() {
        runner.withUserConfiguration(InMemory.class, PrimaryAndNamed.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Clock.class)).isSameAs(ApplicationClock.CLOCK);
            assertFoyerClockIsFuture(context.getBean(FoyerService.class), context.getBean(AdmissionTokenCodec.class));
        });
    }

    @Test
    void coreBeansStartWithoutSpringWebOnTheClasspath() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.http", "org.springframework.web"))
                .withUserConfiguration(InMemory.class)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(FoyerService.class)
                            .hasSingleBean(AdmissionGate.class)
                            .hasSingleBean(RoomRegistry.class)
                            .doesNotHaveBean("foyerQueueHandler");
                    FoyerService service = context.getBean(FoyerService.class);
                    FoyerService.Outcome outcome = service.enter("demo", "v1").toCompletableFuture().get(5, SECONDS);
                    assertThat(context.getBean(AdmissionGate.class).isValid("demo", outcome.token())).isTrue();
                    assertThat(context.getBean(RoomRegistry.class).find("demo")).isPresent();
                });
    }
}
