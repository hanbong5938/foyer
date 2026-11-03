package dev.foyer.redis;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Close/get lifecycle against a controllable client; no Redis server needed. */
class LazyLettuceConnectionLifecycleTest {

    static final RedisURI URI = RedisURI.create("redis://localhost:1");

    /** Each connectAsync hands out the current {@link #pending} future (or throws {@link #failure}). */
    static final class FakeClient extends RedisClient {
        final AtomicInteger connects = new AtomicInteger();
        volatile CompletableFuture<Object> pending = new CompletableFuture<>();
        volatile RuntimeException failure;

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <K, V> ConnectionFuture<StatefulRedisConnection<K, V>> connectAsync(RedisCodec<K, V> codec, RedisURI uri) {
            connects.incrementAndGet();
            RuntimeException toThrow = failure;
            if (toThrow != null) throw toThrow;
            return ConnectionFuture.from(new InetSocketAddress("localhost", 1), (CompletableFuture) pending);
        }
    }

    /** Connection double recording close calls. */
    static final class FakeConnection {
        final AtomicInteger closes = new AtomicInteger();

        @SuppressWarnings("unchecked")
        StatefulRedisConnection<String, String> proxy() {
            return (StatefulRedisConnection<String, String>) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {StatefulRedisConnection.class},
                    (self, method, args) -> switch (method.getName()) {
                        case "closeAsync" -> {
                            closes.incrementAndGet();
                            yield CompletableFuture.completedFuture(null);
                        }
                        case "close" -> {
                            closes.incrementAndGet();
                            yield null;
                        }
                        case "async" -> null;
                        case "hashCode" -> System.identityHashCode(self);
                        case "equals" -> self == args[0];
                        case "toString" -> "FakeConnection";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    final FakeClient client = new FakeClient();

    @AfterEach
    void shutdown() {
        client.shutdown();
    }

    static void assertClosedFailure(CompletionStage<?> stage) {
        assertThatThrownBy(() -> stage.toCompletableFuture().get(5, SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis connection is closed");
    }

    @Test
    void closeBeforeUseNeverConnects() {
        LazyLettuceConnection lazy = new LazyLettuceConnection(client, URI);
        lazy.close();

        assertClosedFailure(lazy.get());
        assertThat(client.connects).hasValue(0);
    }

    @Test
    void repeatedCloseClosesTheConnectionOnce() throws Exception {
        FakeConnection connection = new FakeConnection();
        client.pending.complete(connection.proxy());
        LazyLettuceConnection lazy = new LazyLettuceConnection(client, URI);
        lazy.get().toCompletableFuture().get(5, SECONDS);

        lazy.close();
        lazy.close();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(connection.closes).hasValue(1));
        assertThat(connection.closes).hasValue(1);
    }

    @Test
    void closeAfterSuccessfulConnectClosesItAndRejectsLaterGets() throws Exception {
        FakeConnection connection = new FakeConnection();
        client.pending.complete(connection.proxy());
        LazyLettuceConnection lazy = new LazyLettuceConnection(client, URI);
        lazy.get().toCompletableFuture().get(5, SECONDS);

        lazy.close();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(connection.closes).hasValue(1));
        assertClosedFailure(lazy.get());
        assertThat(client.connects).hasValue(1);
    }

    @Test
    void connectPendingAtCloseIsClosedOnceItCompletes() {
        LazyLettuceConnection lazy = new LazyLettuceConnection(client, URI);
        CompletionStage<?> inFlight = lazy.get();
        assertThat(client.connects).hasValue(1);
        assertThat(inFlight.toCompletableFuture()).isNotDone();

        lazy.close();
        FakeConnection connection = new FakeConnection();
        assertThat(connection.closes).hasValue(0);
        client.pending.complete(connection.proxy());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(connection.closes).hasValue(1));
        assertClosedFailure(lazy.get());
        assertThat(client.connects).hasValue(1);
    }

    @Test
    void failedConnectPendingAtCloseIsDropped() {
        LazyLettuceConnection lazy = new LazyLettuceConnection(client, URI);
        CompletionStage<?> inFlight = lazy.get();
        lazy.close();

        client.pending.completeExceptionally(new RedisConnectionException("refused"));

        assertThatThrownBy(() -> inFlight.toCompletableFuture().get(5, SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RedisConnectionException.class);
        assertClosedFailure(lazy.get());
        assertThat(client.connects).hasValue(1);
    }

    @Test
    void synchronousConnectFailureIsAFailedStageAndRetried() {
        RuntimeException boom = new IllegalStateException("boom");
        client.failure = boom;
        LazyLettuceConnection lazy = new LazyLettuceConnection(client, URI);

        CompletionStage<?> first = lazy.get();
        assertThatThrownBy(() -> first.toCompletableFuture().get(5, SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isSameAs(boom);

        CompletionStage<?> second = lazy.get();
        assertThatThrownBy(() -> second.toCompletableFuture().get(5, SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(client.connects).hasValue(2);
        lazy.close();
    }
}
