package dev.foyer.spring.metrics;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.RoomStats;
import dev.foyer.core.WaitingRoom;
import dev.foyer.spring.FoyerEvent;
import dev.foyer.spring.FoyerEventListener;
import dev.foyer.spring.RoomRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Micrometer binding.
 * <ul>
 *   <li>{@code foyer.enter} counter, tag {@code room}</li>
 *   <li>{@code foyer.poll} counter, tags {@code room}, {@code state}</li>
 *   <li>{@code foyer.admit} counter, tags {@code room}, {@code fail_open}</li>
 *   <li>{@code foyer.queue.waiting} / {@code foyer.queue.active} gauges per configured room. A scrape never
 *       blocks on the store: reading either gauge starts one asynchronous {@link WaitingRoom#stats} call per
 *       room (at most once per second) and reports the last completed sample, NaN until the first sample
 *       arrives or after a failed/timed-out one.</li>
 * </ul>
 */
public final class FoyerMetrics implements FoyerEventListener, SmartInitializingSingleton {

    private static final long STATS_TIMEOUT_MS = 1_000;
    private static final long REFRESH_INTERVAL_MS = 1_000;

    private final ObjectProvider<MeterRegistry> registryProvider;
    private final WaitingRoom waitingRoom;
    private final RoomRegistry rooms;
    private final Clock clock;
    private volatile MeterRegistry registry;

    public FoyerMetrics(
            ObjectProvider<MeterRegistry> registryProvider, WaitingRoom waitingRoom, RoomRegistry rooms, Clock clock) {
        this.registryProvider = registryProvider;
        this.waitingRoom = waitingRoom;
        this.rooms = rooms;
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        MeterRegistry r = registryProvider.getIfAvailable();
        if (r == null) return;
        registry = r;
        rooms.all().forEach((room, policy) -> {
            RoomSample sample = new RoomSample(room, policy);
            Gauge.builder("foyer.queue.waiting", sample, RoomSample::waiting)
                    .description("visitors waiting in line").tag("room", room).strongReference(true).register(r);
            Gauge.builder("foyer.queue.active", sample, RoomSample::active)
                    .description("admitted visitors with an unexpired admission").tag("room", room)
                    .strongReference(true).register(r);
        });
    }

    @Override
    public void accept(FoyerEvent event) {
        MeterRegistry r = registry;
        if (r == null) return;
        switch (event.kind()) {
            case ENTER -> r.counter("foyer.enter", "room", event.room()).increment();
            case POLL -> r.counter("foyer.poll", "room", event.room(), "state", event.state().name()).increment();
            case ADMIT -> r.counter("foyer.admit", "room", event.room(), "fail_open", Boolean.toString(event.failOpen()))
                    .increment();
        }
    }

    /** Latest counts of one room, shared by its two gauges and refreshed asynchronously at most once per second. */
    private final class RoomSample {
        private final String room;
        private final AdmissionPolicy policy;
        /** Guarded by {@code this}; {@code null} reads as NaN. */
        private RoomStats latest;
        /** Guarded by {@code this}. */
        private long nextRefreshAt;
        /** Guarded by {@code this}. */
        private boolean refreshing;

        RoomSample(String room, AdmissionPolicy policy) {
            this.room = room;
            this.policy = policy;
        }

        double waiting() {
            RoomStats stats = read();
            return stats == null ? Double.NaN : stats.waiting();
        }

        double active() {
            RoomStats stats = read();
            return stats == null ? Double.NaN : stats.active();
        }

        /** Returns the last completed sample immediately, kicking off a refresh when one is due and none is pending. */
        private RoomStats read() {
            RoomStats current;
            boolean start;
            synchronized (this) {
                current = latest;
                long now = clock.millis();
                start = !refreshing && now >= nextRefreshAt;
                if (start) {
                    refreshing = true;
                    nextRefreshAt = now + REFRESH_INTERVAL_MS;
                }
            }
            if (start) refresh();
            return current;
        }

        /**
         * Bridges the store's stage into a future with its own deadline. Whatever settles the bridge first (result,
         * failure or timeout) publishes; a store result arriving after that cannot touch a newer sample.
         */
        private void refresh() {
            CompletableFuture<RoomStats> bridge = new CompletableFuture<RoomStats>()
                    .orTimeout(STATS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            bridge.whenComplete((stats, error) -> publish(error == null ? stats : null));
            try {
                waitingRoom.stats(room, policy).whenComplete((stats, error) -> {
                    if (error == null) bridge.complete(stats);
                    else bridge.completeExceptionally(error);
                });
            } catch (RuntimeException e) {
                bridge.completeExceptionally(e);
            }
        }

        private synchronized void publish(RoomStats stats) {
            latest = stats;
            refreshing = false;
        }
    }
}
