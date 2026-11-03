package dev.foyer.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.PollBackoff;
import dev.foyer.core.QueueState;
import dev.foyer.core.QueueStatus;
import dev.foyer.core.RoomStats;
import dev.foyer.core.WaitingRoomUnavailableException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LettuceWaitingRoomTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;
    static RedisCommands<String, String> sync;

    final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    LettuceWaitingRoom room;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
        sync = connection.sync();
    }

    @AfterAll
    static void disconnect() {
        connection.close();
        client.shutdown();
    }

    @BeforeEach
    void reset() {
        sync.flushall();
        room = new LettuceWaitingRoom(connection.async(), clock, PollBackoff.defaults(), "foyer");
    }

    @Test
    void concurrentEntryAdmitsExactlyRatePerIntervalInEnrollmentOrderWithoutDuplicates() {
        AdmissionPolicy policy = AdmissionPolicy.builder().rate(50).interval(Duration.ofSeconds(1)).build();
        int visitors = 500;
        List<String> ids = IntStream.range(0, visitors).mapToObj(i -> "v" + i).toList();

        List<CompletableFuture<QueueStatus>> enters = ids.stream()
                .map(id -> room.enter("concert", id, policy).toCompletableFuture())
                .toList();
        CompletableFuture.allOf(enters.toArray(CompletableFuture[]::new)).join();

        // Fresh bucket is full: the first `burst` to be enrolled were admitted at enter time.
        Set<String> admitted = new HashSet<>();
        for (int i = 0; i < visitors; i++) {
            if (enters.get(i).join().isAdmitted()) admitted.add(ids.get(i));
        }
        assertThat(admitted).hasSize(50);

        List<String> enrollmentOrder = sync.zrange("foyer:{concert}:queue", 0, -1);
        assertThat(enrollmentOrder).hasSize(visitors - 50).doesNotContainAnyElementsOf(admitted);

        List<String> remaining = new ArrayList<>(enrollmentOrder);
        int round = 0;
        while (!remaining.isEmpty()) {
            round++;
            clock.advance(Duration.ofSeconds(1));
            List<String> shuffled = new ArrayList<>(remaining);
            Collections.shuffle(shuffled);
            List<CompletableFuture<QueueStatus>> polls = shuffled.stream()
                    .map(id -> room.poll("concert", id, policy).toCompletableFuture())
                    .toList();
            CompletableFuture.allOf(polls.toArray(CompletableFuture[]::new)).join();

            Set<String> admittedThisRound = new HashSet<>();
            for (int i = 0; i < shuffled.size(); i++) {
                QueueStatus status = polls.get(i).join();
                assertThat(status.state()).isNotEqualTo(QueueState.NOT_IN_QUEUE);
                if (status.isAdmitted()) admittedThisRound.add(shuffled.get(i));
            }
            // Exactly the next `rate` in enrollment order, regardless of poll order; nobody twice.
            List<String> expected = remaining.subList(0, Math.min(50, remaining.size()));
            assertThat(admittedThisRound).as("round %d", round).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(admitted).doesNotContainAnyElementsOf(admittedThisRound);
            admitted.addAll(admittedThisRound);
            remaining = new ArrayList<>(remaining.subList(expected.size(), remaining.size()));
        }
        assertThat(round).isEqualTo(9);
        assertThat(admitted).hasSize(visitors);
        assertThat(room.stats("concert", policy).toCompletableFuture().join().waiting()).isZero();
    }

    @Test
    void maxActiveCapsConcurrentAdmissionsUntilTokensExpire() {
        AdmissionPolicy policy = AdmissionPolicy.builder()
                .rate(100).interval(Duration.ofSeconds(1)).maxActive(5).tokenTtl(Duration.ofSeconds(10)).build();
        List<String> ids = IntStream.range(0, 20).mapToObj(i -> "v" + i).toList();
        long admitted = ids.stream().filter(id -> enter(id, policy).isAdmitted()).count();
        assertThat(admitted).isEqualTo(5);
        assertThat(room.stats("concert", policy).toCompletableFuture().join()).satisfies(s -> {
            assertThat(s.active()).isEqualTo(5);
            assertThat(s.waiting()).isEqualTo(15);
        });

        clock.advance(Duration.ofSeconds(5));
        assertThat(ids.stream().filter(id -> poll(id, policy).isAdmitted()).count()).isEqualTo(5); // same five

        clock.advance(Duration.ofSeconds(6)); // first batch expired
        Set<String> second = new HashSet<>();
        for (String id : ids) if (poll(id, policy).isAdmitted()) second.add(id);
        assertThat(second).hasSize(5).containsExactlyInAnyOrder("v5", "v6", "v7", "v8", "v9");
        // Expired visitors are gone, not re-queued.
        assertThat(poll("v0", policy).state()).isEqualTo(QueueState.NOT_IN_QUEUE);
    }

    @Test
    void admissionIsReReportedUntilExpiry() {
        AdmissionPolicy policy = AdmissionPolicy.builder().rate(1).tokenTtl(Duration.ofSeconds(30)).build();
        QueueStatus first = enter("a", policy);
        assertThat(first.isAdmitted()).isTrue();
        assertThat(first.admittedUntil()).isEqualTo(clock.instant().plusSeconds(30));

        clock.advance(Duration.ofSeconds(29));
        assertThat(poll("a", policy)).isEqualTo(new QueueStatus(QueueState.ADMITTED, -1, 0, first.admittedUntil(), null));
        assertThat(enter("a", policy).admittedUntil()).isEqualTo(first.admittedUntil());

        clock.advance(Duration.ofSeconds(2));
        assertThat(poll("a", policy).state()).isEqualTo(QueueState.NOT_IN_QUEUE);
    }

    @Test
    void idleWaitersAreEvictedSoTheLineKeepsMoving() {
        AdmissionPolicy policy = AdmissionPolicy.builder()
                .rate(1).interval(Duration.ofMinutes(1)).queueTtl(Duration.ofSeconds(5)).build();
        assertThat(enter("front", policy).isAdmitted()).isTrue(); // consumes the single token
        assertThat(enter("idle", policy).position()).isEqualTo(0);
        assertThat(enter("patient", policy).position()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(3));
        QueueStatus still = poll("patient", policy);
        assertThat(still.position()).isEqualTo(1);
        assertThat(still.retryAfter()).isEqualTo(Duration.ofSeconds(10)); // clamped to max

        clock.advance(Duration.ofSeconds(3)); // "idle" last seen 6s ago
        assertThat(poll("patient", policy).position()).isEqualTo(0);
        assertThat(poll("idle", policy).state()).isEqualTo(QueueState.NOT_IN_QUEUE);
        assertThat(enter("idle", policy).position()).isEqualTo(1); // re-entering goes to the back
    }

    @Test
    void expiredWaiterBeyondThePruneBatchIsNotRevived() {
        AdmissionPolicy policy = AdmissionPolicy.builder()
                .rate(1).interval(Duration.ofMinutes(1)).queueTtl(Duration.ofSeconds(5)).build();
        assertThat(enter("front", policy).isAdmitted()).isTrue();
        // 150 idle waiters with distinct last-seen times: more than one bounded prune (100) removes per call.
        for (int i = 0; i < 150; i++) {
            clock.advance(Duration.ofMillis(1));
            enter("w" + i, policy);
        }
        clock.advance(Duration.ofSeconds(6));

        // Newest in `seen`, so the oldest-100 batch prune skips it; it must still be gone, not refreshed in place.
        assertThat(poll("w149", policy).state()).isEqualTo(QueueState.NOT_IN_QUEUE);
        assertThat(enter("w149", policy).position()).as("re-enters fresh").isEqualTo(0);
        assertThat(room.stats("concert", policy).toCompletableFuture().join().waiting()).isEqualTo(1);
    }

    @Test
    void statsStopCountingIdleWaitersAtQueueTtlWithoutTouchingTheLine() {
        AdmissionPolicy policy = AdmissionPolicy.builder()
                .rate(1).interval(Duration.ofMinutes(1))
                .queueTtl(Duration.ofSeconds(5)).tokenTtl(Duration.ofMinutes(5)).build();
        assertThat(enter("front", policy).isAdmitted()).isTrue();
        assertThat(enter("a", policy).position()).isEqualTo(0);
        assertThat(enter("b", policy).position()).isEqualTo(1);
        Map<String, Double> seenBefore = seenScores();

        assertStats(policy, 2, 1);
        clock.advance(Duration.ofMillis(4999));
        assertStats(policy, 2, 1);
        // Exactly queueTtl idle: admit.lua evicts at `seen <= now - queueTtl`, so sampling must agree. No enter/poll since.
        clock.advance(Duration.ofMillis(1));
        assertStats(policy, 0, 1);

        // Sampling is read-only for the line: nothing pruned, nobody refreshed.
        assertThat(sync.zrange("foyer:{concert}:queue", 0, -1)).containsExactly("a", "b");
        assertThat(seenScores()).isEqualTo(seenBefore).containsOnlyKeys("a", "b");
    }

    @Test
    void statsCountEveryWaiterWhenQueueTtlIsDisabled() {
        AdmissionPolicy policy = AdmissionPolicy.builder()
                .rate(1).interval(Duration.ofMinutes(1))
                .queueTtl(Duration.ZERO).tokenTtl(Duration.ofMinutes(5)).build();
        assertThat(enter("front", policy).isAdmitted()).isTrue();
        enter("a", policy);
        enter("b", policy);

        clock.advance(Duration.ofMinutes(4));
        assertStats(policy, 2, 1);
        clock.advance(Duration.ofHours(1));
        assertStats(policy, 2, 0); // the admission expired; idle waiters never do
        assertThat(sync.zcard("foyer:{concert}:queue")).isEqualTo(2);
    }

    @Test
    void statsCountOnlyFreshWaitersAndUnexpiredAdmissions() {
        AdmissionPolicy policy = AdmissionPolicy.builder()
                .rate(1).interval(Duration.ofMinutes(1))
                .queueTtl(Duration.ofSeconds(5)).tokenTtl(Duration.ofSeconds(6)).build();
        assertThat(enter("front", policy).isAdmitted()).isTrue(); // admitted until t=6s
        enter("stale", policy); // last seen t=0
        clock.advance(Duration.ofSeconds(3));
        assertThat(enter("fresh", policy).position()).isEqualTo(1); // last seen t=3s

        clock.advance(Duration.ofSeconds(2)); // t=5s: stale idle exactly queueTtl, fresh idle 2s
        assertStats(policy, 1, 1);

        clock.advance(Duration.ofSeconds(2)); // t=7s: front's admission expired, fresh idle 4s
        assertStats(policy, 1, 0);

        clock.advance(Duration.ofSeconds(1)); // t=8s: fresh idle exactly queueTtl
        assertStats(policy, 0, 0);
        assertThat(sync.zcard("foyer:{concert}:queue")).isEqualTo(2);
        assertThat(sync.zcard("foyer:{concert}:seen")).isEqualTo(2);
    }

    @Test
    void leaveRemovesWaiterAndPollDoesNotEnroll() {
        AdmissionPolicy policy = AdmissionPolicy.builder().rate(1).interval(Duration.ofMinutes(1)).build();
        enter("front", policy);
        enter("x", policy);
        assertThat(poll("stranger", policy).state()).isEqualTo(QueueState.NOT_IN_QUEUE);
        room.leave("concert", "x").toCompletableFuture().join();
        assertThat(poll("x", policy).state()).isEqualTo(QueueState.NOT_IN_QUEUE);
        assertThat(room.stats("concert", policy).toCompletableFuture().join().waiting()).isZero();
    }

    @Test
    void recoversFromScriptFlush() {
        AdmissionPolicy policy = AdmissionPolicy.builder().rate(1).interval(Duration.ofMinutes(1)).build();
        enter("a", policy);
        sync.scriptFlush();
        assertThat(poll("a", policy).state()).isEqualTo(QueueState.ADMITTED);
    }

    @Test
    void connectionFailureSurfacesAsUnavailable() {
        StatefulRedisConnection<String, String> closing = client.connect();
        LettuceWaitingRoom broken = new LettuceWaitingRoom(closing.async());
        closing.close();
        assertThatThrownBy(() -> broken.poll("concert", "a", AdmissionPolicy.builder().build())
                        .toCompletableFuture().join())
                .hasCauseInstanceOf(WaitingRoomUnavailableException.class);
    }

    private QueueStatus enter(String id, AdmissionPolicy policy) {
        return room.enter("concert", id, policy).toCompletableFuture().join();
    }

    private QueueStatus poll(String id, AdmissionPolicy policy) {
        return room.poll("concert", id, policy).toCompletableFuture().join();
    }

    private void assertStats(AdmissionPolicy policy, long waiting, long active) {
        RoomStats stats = room.stats("concert", policy).toCompletableFuture().join();
        assertThat(stats.waiting()).as("waiting at %s", clock.instant()).isEqualTo(waiting);
        assertThat(stats.active()).as("active at %s", clock.instant()).isEqualTo(active);
    }

    private Map<String, Double> seenScores() {
        Map<String, Double> scores = new HashMap<>();
        sync.zrangeWithScores("foyer:{concert}:seen", 0, -1).forEach(s -> scores.put(s.getValue(), s.getScore()));
        return scores;
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
