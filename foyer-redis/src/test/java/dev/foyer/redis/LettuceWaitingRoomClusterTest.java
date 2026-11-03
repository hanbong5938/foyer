package dev.foyer.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.PollBackoff;
import dev.foyer.core.QueueState;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Same script through {@link RedisClusterClient}: proves the hash-tagged keys route as one slot and that the
 * cluster command interface satisfies {@link LettuceWaitingRoom}. One node owning all 16384 slots is enough for
 * routing; multi-node failover is Lettuce's concern.
 */
@Testcontainers
class LettuceWaitingRoomClusterTest {

    static final int PORT = 7379; // not 7000: macOS AirPlay listens there

    /** Fixed host port: the node announces 127.0.0.1:PORT and cluster clients reconnect to announced addresses. */
    @Container
    static final GenericContainer<?> NODE = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(PORT)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindPort(PORT),
                            new com.github.dockerjava.api.model.ExposedPort(PORT))))
            .withCommand("sh", "-c",
                    "redis-server --port " + PORT + " --cluster-enabled yes --cluster-announce-ip 127.0.0.1"
                    + " --cluster-announce-port " + PORT + " --save '' & "
                    + "sleep 1 && redis-cli -p " + PORT + " cluster addslotsrange 0 16383 && wait")
            .waitingFor(Wait.forLogMessage(".*Cluster state changed: ok.*", 1));

    static RedisClusterClient client;

    @BeforeAll
    static void connect() {
        client = RedisClusterClient.create(RedisURI.create("127.0.0.1", PORT));
    }

    @AfterAll
    static void shutdown() {
        client.shutdown();
    }

    @Test
    void admitsThroughClusterClient() {
        try (LazyLettuceConnection lazy = new LazyLettuceConnection(client)) {
            LettuceWaitingRoom room = new LettuceWaitingRoom(lazy, Clock.systemUTC(), PollBackoff.defaults(), "cl");
            AdmissionPolicy policy = AdmissionPolicy.builder().rate(1).interval(Duration.ofMinutes(1)).build();
            assertThat(room.enter("show", "a", policy).toCompletableFuture().join().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(room.enter("show", "b", policy).toCompletableFuture().join().position()).isZero();
            assertThat(room.poll("show", "a", policy).toCompletableFuture().join().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(room.stats("show", policy).toCompletableFuture().join().waiting()).isEqualTo(1);
            room.leave("show", "b").toCompletableFuture().join();
            assertThat(room.poll("show", "b", policy).toCompletableFuture().join().state()).isEqualTo(QueueState.NOT_IN_QUEUE);
        }
    }
}
