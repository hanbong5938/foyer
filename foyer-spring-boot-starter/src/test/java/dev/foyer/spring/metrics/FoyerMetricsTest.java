package dev.foyer.spring.metrics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.PollBackoff;
import dev.foyer.core.QueueState;
import dev.foyer.core.QueueStatus;
import dev.foyer.core.RoomStats;
import dev.foyer.core.WaitingRoom;
import dev.foyer.core.WaitingRoomUnavailableException;
import dev.foyer.redis.LettuceWaitingRoom;
import dev.foyer.spring.FoyerProperties;
import dev.foyer.spring.RoomRegistry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.containers.GenericContainer;

/** Queue gauges sample the store asynchronously: reads never block and share one refresh per room. */
class FoyerMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock();
    private final ControlledStore store = new ControlledStore();

    @Test
    void withoutRegistryNoStatsAreRequested() {
        FoyerMetrics metrics = new FoyerMetrics(provider(null), store, rooms(), clock);

        metrics.afterSingletonsInstantiated();

        assertThat(registry.getMeters()).isEmpty();
        assertThat(store.calls).hasValue(0);
    }

    @Test
    void pendingFirstSampleReadsNaNWithoutBlockingThenPublishesBothCounts() {
        register();

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            assertThat(waiting()).isNaN();
            assertThat(active()).isNaN();
        });
        assertThat(store.calls).hasValue(1);

        store.latest().complete(new RoomStats(3, 2));

        assertThat(waiting()).isEqualTo(3.0);
        assertThat(active()).isEqualTo(2.0);
        assertThat(store.calls).hasValue(1);
    }

    @Test
    void refreshesAtMostOncePerIntervalAndServesPreviousSampleWhilePending() {
        register();
        sample(3, 2);

        clock.advance(999);
        waiting();
        active();
        assertThat(store.calls).hasValue(1);

        clock.advance(1);
        assertThat(waiting()).isEqualTo(3.0);
        assertThat(active()).isEqualTo(2.0);
        assertThat(store.calls).hasValue(2);

        clock.advance(5_000);
        for (int i = 0; i < 5; i++) {
            assertThat(waiting()).isEqualTo(3.0);
            assertThat(active()).isEqualTo(2.0);
        }
        assertThat(store.calls).hasValue(2);

        store.latest().complete(new RoomStats(5, 1));

        assertThat(waiting()).isEqualTo(5.0);
        assertThat(active()).isEqualTo(1.0);
    }

    @Test
    void failedRefreshClearsSampleAndNextRefreshRecovers() {
        register();
        sample(3, 2);

        clock.advance(1_000);
        waiting();
        store.latest().completeExceptionally(
                new WaitingRoomUnavailableException("redis call failed", new RuntimeException("down")));

        assertThat(waiting()).isNaN();
        assertThat(active()).isNaN();

        clock.advance(1_000);
        waiting();
        assertThat(store.calls).hasValue(3);
        store.latest().complete(new RoomStats(1, 1));

        assertThat(waiting()).isEqualTo(1.0);
        assertThat(active()).isEqualTo(1.0);
    }

    @Test
    void synchronousStatsFailureReadsNaNAndIsRetried() {
        store.throwing = true;
        register();

        assertThat(waiting()).isNaN();
        assertThat(active()).isNaN();
        assertThat(store.calls).hasValue(1);

        clock.advance(1_000);
        assertThat(waiting()).isNaN();
        assertThat(store.calls).hasValue(2);

        store.throwing = false;
        clock.advance(1_000);
        waiting();
        store.latest().complete(new RoomStats(4, 0));
        assertThat(waiting()).isEqualTo(4.0);
        assertThat(active()).isEqualTo(0.0);
    }

    @Test
    void timedOutRefreshClearsSampleAndLateResultCannotOverwriteNewerSample() {
        register();
        sample(4, 4);

        clock.advance(1_000);
        waiting();
        CompletableFuture<RoomStats> stale = store.latest();
        assertThat(store.calls).hasValue(2);

        await().atMost(3, SECONDS).untilAsserted(() -> assertThat(waiting()).isNaN());
        assertThat(active()).isNaN();
        assertThat(store.calls).hasValue(2);

        clock.advance(1_000);
        waiting();
        assertThat(store.calls).hasValue(3);
        CompletableFuture<RoomStats> fresh = store.latest();

        fresh.complete(new RoomStats(2, 2));
        stale.complete(new RoomStats(9, 9));

        assertThat(waiting()).isEqualTo(2.0);
        assertThat(active()).isEqualTo(2.0);
    }

    @Test
    void concurrentReadsStartOneRefresh() throws Exception {
        register();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> reads = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                reads.add(pool.submit(() -> {
                    start.await(5, SECONDS);
                    waiting();
                    active();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> read : reads) {
                read.get(5, SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.calls).hasValue(1);
    }

    /**
     * Real Redis: each room's gauge counts only waiters still live under that room's own queue TTL. Nobody enters
     * or polls after the clock moves, so the stale short-room waiters are never lazily evicted from the queue.
     */
    @Test
    void liveWaitingGaugesFollowEachRoomQueueTtlWithoutPolling() {
        try (GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            redis.start();
            RedisClient client = RedisClient.create(RedisURI.create(redis.getHost(), redis.getMappedPort(6379)));
            try (StatefulRedisConnection<String, String> connection = client.connect()) {
                FoyerProperties properties = new FoyerProperties();
                properties.getPoll().setMin(Duration.ofMillis(500));
                properties.getPoll().setMax(Duration.ofSeconds(1));
                properties.getRooms().put("short", ttlRoom(Duration.ofSeconds(5)));
                properties.getRooms().put("long", ttlRoom(Duration.ofHours(1)));
                properties.getRooms().put("disabled", ttlRoom(Duration.ZERO));
                RoomRegistry rooms = new RoomRegistry(properties);
                WaitingRoom redisStore = new LettuceWaitingRoom(connection.async(), clock,
                        new PollBackoff(properties.getPoll().getMin(), properties.getPoll().getMax()),
                        "foyer-metrics-test");

                for (String room : List.of("short", "long", "disabled")) {
                    AdmissionPolicy policy = rooms.require(room);
                    assertThat(join(redisStore.enter(room, "front", policy)).isAdmitted()).isTrue();
                    assertThat(join(redisStore.enter(room, "a", policy)).state()).isEqualTo(QueueState.WAITING);
                    assertThat(join(redisStore.enter(room, "b", policy)).state()).isEqualTo(QueueState.WAITING);
                }

                new FoyerMetrics(provider(registry), redisStore, rooms, clock).afterSingletonsInstantiated();
                awaitGauges(registry, 2, 2, 2);

                clock.advance(4_000);
                awaitGauges(registry, 2, 2, 2);

                clock.advance(1_000);
                awaitGauges(registry, 0, 2, 2);

                // A fresh binding's first sample is necessarily taken after the advance, so the unchanged long and
                // disabled counts cannot be leftovers from the earlier samples.
                SimpleMeterRegistry fresh = new SimpleMeterRegistry();
                new FoyerMetrics(provider(fresh), redisStore, rooms, clock).afterSingletonsInstantiated();
                awaitGauges(fresh, 0, 2, 2);
            } finally {
                client.shutdown();
            }
        }
    }

    private static FoyerProperties.Room ttlRoom(Duration queueTtl) {
        FoyerProperties.Room room = new FoyerProperties.Room();
        room.setRate(1);
        room.setInterval(Duration.ofHours(1));
        room.setTokenTtl(Duration.ofMinutes(10));
        room.setQueueTtl(queueTtl);
        return room;
    }

    private static void awaitGauges(MeterRegistry meters, double shortWaiting, double longWaiting,
            double disabledWaiting) {
        await().atMost(5, SECONDS).untilAsserted(() -> {
            assertThat(gauge(meters, "foyer.queue.waiting", "short")).isEqualTo(shortWaiting);
            assertThat(gauge(meters, "foyer.queue.waiting", "long")).isEqualTo(longWaiting);
            assertThat(gauge(meters, "foyer.queue.waiting", "disabled")).isEqualTo(disabledWaiting);
            assertThat(gauge(meters, "foyer.queue.active", "short")).isEqualTo(1.0);
            assertThat(gauge(meters, "foyer.queue.active", "long")).isEqualTo(1.0);
            assertThat(gauge(meters, "foyer.queue.active", "disabled")).isEqualTo(1.0);
        });
    }

    private static double gauge(MeterRegistry meters, String name, String room) {
        return meters.get(name).tag("room", room).gauge().value();
    }

    private static <T> T join(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private void sample(long waiting, long active) {
        waiting();
        store.latest().complete(new RoomStats(waiting, active));
        assertThat(waiting()).isEqualTo((double) waiting);
    }

    private void register() {
        new FoyerMetrics(provider(registry), store, rooms(), clock).afterSingletonsInstantiated();
    }

    private double waiting() {
        return registry.get("foyer.queue.waiting").tag("room", "demo").gauge().value();
    }

    private double active() {
        return registry.get("foyer.queue.active").tag("room", "demo").gauge().value();
    }

    private static RoomRegistry rooms() {
        FoyerProperties properties = new FoyerProperties();
        properties.getRooms().put("demo", new FoyerProperties.Room());
        return new RoomRegistry(properties);
    }

    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (registry != null) beans.registerSingleton("meterRegistry", registry);
        return beans.getBeanProvider(MeterRegistry.class);
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000);

        void advance(long ms) {
            millis.addAndGet(ms);
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    /** {@code stats} hands out a fresh pending future per call (or throws); queue operations are unused. */
    private static final class ControlledStore implements WaitingRoom {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean throwing;
        private volatile CompletableFuture<RoomStats> latest;

        CompletableFuture<RoomStats> latest() {
            return latest;
        }

        @Override
        public CompletionStage<RoomStats> stats(String room, AdmissionPolicy policy) {
            calls.incrementAndGet();
            if (throwing) throw new IllegalStateException("stats exploded");
            CompletableFuture<RoomStats> future = new CompletableFuture<>();
            latest = future;
            return future;
        }

        @Override
        public CompletionStage<QueueStatus> enter(String room, String visitorId, AdmissionPolicy policy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<QueueStatus> poll(String room, String visitorId, AdmissionPolicy policy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> leave(String room, String visitorId) {
            throw new UnsupportedOperationException();
        }
    }
}
