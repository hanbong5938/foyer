package dev.foyer.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.PollBackoff;
import dev.foyer.core.WaitingRoomUnavailableException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Clock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LazyLettuceConnectionTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedisClient client;

    @BeforeAll
    static void start() {
        client = RedisClient.create();
    }

    @AfterAll
    static void stop() {
        client.shutdown();
    }

    @Test
    void failedConnectIsReportedAsUnavailableAndRetriedOnNextCall() {
        LazyLettuceConnection lazy = new LazyLettuceConnection(client, RedisURI.create("redis://localhost:1"));
        LettuceWaitingRoom room = new LettuceWaitingRoom(lazy, Clock.systemUTC(), PollBackoff.defaults(), "t");
        AdmissionPolicy policy = AdmissionPolicy.builder().build();
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> room.enter("r", "v", policy).toCompletableFuture().join())
                    .hasCauseInstanceOf(WaitingRoomUnavailableException.class);
        }
        lazy.close(); // nothing to close, must not throw
    }

    @Test
    void connectsOnceAndReusesTheConnection() {
        RedisURI uri = RedisURI.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        try (LazyLettuceConnection lazy = new LazyLettuceConnection(client, uri)) {
            var first = lazy.get().toCompletableFuture().join();
            var second = lazy.get().toCompletableFuture().join();
            assertThat(first).isSameAs(second);
            LettuceWaitingRoom room = new LettuceWaitingRoom(lazy, Clock.systemUTC(), PollBackoff.defaults(), "t");
            assertThat(room.enter("r", "v", AdmissionPolicy.builder().build()).toCompletableFuture().join().isAdmitted())
                    .isTrue();
        }
    }
}
