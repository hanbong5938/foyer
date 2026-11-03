package dev.foyer.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Durations are handed to the store as whole milliseconds, so the policy only accepts what survives that. */
class AdmissionPolicyTest {

    static final Duration OVERFLOW = Duration.ofSeconds(Long.MAX_VALUE);
    static final List<Duration> NEVER_VALID = List.of(
            Duration.ofMillis(-1), Duration.ofNanos(500_000), Duration.ofNanos(1_500_000), OVERFLOW);

    static AdmissionPolicy.Builder with(String field, Duration value) {
        AdmissionPolicy.Builder b = AdmissionPolicy.builder();
        return switch (field) {
            case "interval" -> b.interval(value);
            case "tokenTtl" -> b.tokenTtl(value);
            case "queueTtl" -> b.queueTtl(value);
            default -> throw new IllegalArgumentException(field);
        };
    }

    static Function<AdmissionPolicy, Duration> get(String field) {
        return switch (field) {
            case "interval" -> AdmissionPolicy::interval;
            case "tokenTtl" -> AdmissionPolicy::tokenTtl;
            case "queueTtl" -> AdmissionPolicy::queueTtl;
            default -> throw new IllegalArgumentException(field);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"interval", "tokenTtl", "queueTtl"})
    void rejectsNullNegativeSubMillisecondFractionalAndOverflowingDurations(String field) {
        assertThatThrownBy(() -> with(field, null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(field + " must be");
        for (Duration bad : NEVER_VALID) {
            assertThatThrownBy(() -> with(field, bad).build())
                    .as("%s = %s", field, bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith(field + " must be")
                    .hasMessageContaining("whole-millisecond");
        }
        assertThatThrownBy(() -> with(field, OVERFLOW).build()).hasCauseInstanceOf(ArithmeticException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"interval", "tokenTtl"})
    void zeroIsRejectedWhereTheStoreWouldNeverRefillOrExpire(String field) {
        assertThatThrownBy(() -> with(field, Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(field + " must be a positive whole-millisecond duration representable as a long");
    }

    @Test
    void zeroQueueTtlIsKeptAsIs() {
        assertThat(with("queueTtl", Duration.ZERO).build().queueTtl()).isEqualTo(Duration.ZERO);
        assertThatThrownBy(() -> with("queueTtl", Duration.ofMillis(-1)).build())
                .hasMessage("queueTtl must be zero or a positive whole-millisecond duration representable as a long");
    }

    @ParameterizedTest
    @ValueSource(strings = {"interval", "tokenTtl", "queueTtl"})
    void wholeMillisecondsAndDefaultsAreKeptUnchanged(String field) {
        Duration oneMs = Duration.ofMillis(1);
        assertThat(get(field).apply(with(field, oneMs).build())).isSameAs(oneMs);
        Duration large = Duration.ofDays(365);
        assertThat(get(field).apply(with(field, large).build())).isSameAs(large);
    }

    @Test
    void defaultsAreValid() {
        AdmissionPolicy policy = AdmissionPolicy.builder().build();
        assertThat(policy.interval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.tokenTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.queueTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.burst()).isEqualTo(policy.rate());
    }

    @Test
    void invalidPolicyNeverReachesTheStore() {
        AtomicInteger calls = new AtomicInteger();
        WaitingRoom store = new WaitingRoom() {
            @Override
            public CompletionStage<QueueStatus> enter(String room, String visitorId, AdmissionPolicy policy) {
                calls.incrementAndGet();
                throw new AssertionError("store invoked with " + policy);
            }

            @Override
            public CompletionStage<QueueStatus> poll(String room, String visitorId, AdmissionPolicy policy) {
                return enter(room, visitorId, policy);
            }

            @Override
            public CompletionStage<Void> leave(String room, String visitorId) {
                throw new AssertionError();
            }

            @Override
            public CompletionStage<RoomStats> stats(String room, AdmissionPolicy policy) {
                throw new AssertionError();
            }
        };
        assertThatThrownBy(() -> store.enter("r", "v", AdmissionPolicy.builder().interval(Duration.ofNanos(500_000)).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }
}
