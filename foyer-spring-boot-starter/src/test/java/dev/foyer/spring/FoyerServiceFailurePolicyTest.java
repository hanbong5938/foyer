package dev.foyer.spring;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.HmacTokenCodec;
import dev.foyer.core.QueueState;
import dev.foyer.core.QueueStatus;
import dev.foyer.core.RoomStats;
import dev.foyer.core.WaitingRoom;
import dev.foyer.core.WaitingRoomUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The fail-open/fail-closed policy applied by {@link FoyerService} to store failures. */
class FoyerServiceFailurePolicyTest {

    private static final String VISITOR = "3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void leaveToleratesUnavailableStoreWhenFailOpen() throws Exception {
        FailingStore store = new FailingStore(unavailable());

        assertThat(service(store, true).leave("demo", VISITOR).toCompletableFuture().get(5, SECONDS)).isNull();
        assertThat(store.calls).hasValue(1);
    }

    @Test
    void leavePropagatesUnavailableStoreWhenFailClosed() {
        FailingStore store = new FailingStore(unavailable());

        assertFailsWith(service(store, false).leave("demo", VISITOR), WaitingRoomUnavailableException.class);
    }

    @Test
    void leavePropagatesUnexpectedErrorsInBothModes() {
        assertFailsWith(service(new FailingStore(new IllegalStateException("bug")), true).leave("demo", VISITOR),
                IllegalStateException.class);
        assertFailsWith(service(new FailingStore(new IllegalStateException("bug")), false).leave("demo", VISITOR),
                IllegalStateException.class);
    }

    @Test
    void leavePropagatesWrappedUnexpectedErrorsUnwrapped() {
        FailingStore store = new FailingStore(new CompletionException(new IllegalStateException("bug")));

        assertFailsWith(service(store, true).leave("demo", VISITOR), IllegalStateException.class);
    }

    @Test
    void leaveToleratesWrappedUnavailableStoreWhenFailOpen() throws Exception {
        FailingStore store = new FailingStore(new ExecutionException(unavailable()));

        assertThat(service(store, true).leave("demo", VISITOR).toCompletableFuture().get(5, SECONDS)).isNull();
    }

    @Test
    void leaveUnknownRoomThrowsWithoutTouchingStore() {
        FailingStore store = new FailingStore(unavailable());

        assertThatThrownBy(() -> service(store, true).leave("nope", VISITOR)).isInstanceOf(UnknownRoomException.class);
        assertThat(store.calls).hasValue(0);
    }

    @Test
    void enterAndPollAdmitWhenStoreUnavailableAndFailOpen() throws Exception {
        FoyerService service = service(new FailingStore(unavailable()), true);

        FoyerService.Outcome entered = service.enter("demo", VISITOR).toCompletableFuture().get(5, SECONDS);
        FoyerService.Outcome polled = service.poll("demo", VISITOR).toCompletableFuture().get(5, SECONDS);

        for (FoyerService.Outcome outcome : new FoyerService.Outcome[] {entered, polled}) {
            assertThat(outcome.status().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(outcome.token()).isNotNull();
            assertThat(outcome.failOpen()).isTrue();
        }
    }

    @Test
    void failOpenTokensExpireExactlyAtTheAdmissionDeadline() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00.100000500Z");
        Instant deadline = Instant.parse("2026-01-01T00:00:00.101000500Z");
        FoyerProperties properties = new FoyerProperties();
        FoyerProperties.Room demo = new FoyerProperties.Room();
        demo.setTokenTtl(Duration.ofMillis(1));
        properties.getRooms().put("demo", demo);
        HmacTokenCodec codec = new HmacTokenCodec(SECRET, Clock.fixed(now, ZoneOffset.UTC));
        FoyerService service = new FoyerService(new FailingStore(unavailable()), codec,
                new RoomRegistry(properties), true, Clock.fixed(now, ZoneOffset.UTC), e -> {});

        FoyerService.Outcome entered = service.enter("demo", VISITOR).toCompletableFuture().get(5, SECONDS);
        FoyerService.Outcome polled = service.poll("demo", VISITOR).toCompletableFuture().get(5, SECONDS);

        for (FoyerService.Outcome outcome : new FoyerService.Outcome[] {entered, polled}) {
            assertThat(outcome.failOpen()).isTrue();
            assertThat(outcome.status().admittedUntil()).isEqualTo(deadline);
            assertThat(codec.decode(outcome.token())).hasValueSatisfying(token -> {
                assertThat(token.issuedAt()).isEqualTo(now);
                assertThat(token.expiresAt()).isEqualTo(outcome.status().admittedUntil());
            });
            assertThat(new HmacTokenCodec(SECRET, Clock.fixed(deadline.minusNanos(1), ZoneOffset.UTC)).decode(outcome.token())).isPresent();
            assertThat(new HmacTokenCodec(SECRET, Clock.fixed(deadline, ZoneOffset.UTC)).decode(outcome.token())).isEmpty();
        }
    }

    @Test
    void enterPropagatesUnavailableStoreWhenFailClosed() {
        FoyerService service = service(new FailingStore(unavailable()), false);

        assertFailsWith(service.enter("demo", VISITOR), WaitingRoomUnavailableException.class);
        assertFailsWith(service.poll("demo", VISITOR), WaitingRoomUnavailableException.class);
    }

    @Test
    void enterDoesNotFailOpenOnUnexpectedErrors() {
        FoyerService service = service(new FailingStore(new IllegalStateException("bug")), true);

        assertFailsWith(service.enter("demo", VISITOR), IllegalStateException.class);
        assertFailsWith(service.poll("demo", VISITOR), IllegalStateException.class);
    }

    @Test
    void throwingListenerDoesNotChangeAdmissionOutcomes() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        Consumer<FoyerEvent> listener = e -> { seen.incrementAndGet(); throw new IllegalStateException("listener bug"); };

        FoyerService.Outcome admitted = service(new AdmittingStore(), true, listener)
                .enter("demo", VISITOR).toCompletableFuture().get(5, SECONDS);
        assertThat(admitted.status().state()).isEqualTo(QueueState.ADMITTED);
        assertThat(admitted.token()).isNotNull();
        assertThat(seen).hasValue(2); // ENTER + ADMIT both dispatched despite the first throwing

        FoyerService.Outcome failOpen = service(new FailingStore(unavailable()), true, listener)
                .poll("demo", VISITOR).toCompletableFuture().get(5, SECONDS);
        assertThat(failOpen.failOpen()).isTrue();
        assertThat(failOpen.token()).isNotNull();
    }

    private static void assertFailsWith(CompletionStage<?> stage, Class<? extends Throwable> cause) {
        assertThatThrownBy(() -> stage.toCompletableFuture().get(5, SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(cause);
    }

    private static WaitingRoomUnavailableException unavailable() {
        return new WaitingRoomUnavailableException("redis call failed", new RuntimeException("connection refused"));
    }

    private static FoyerService service(WaitingRoom store, boolean failOpen) {
        return service(store, failOpen, e -> {});
    }

    private static FoyerService service(WaitingRoom store, boolean failOpen, Consumer<FoyerEvent> events) {
        FoyerProperties properties = new FoyerProperties();
        properties.getRooms().put("demo", new FoyerProperties.Room());
        return new FoyerService(store, new HmacTokenCodec("0123456789abcdef0123456789abcdef"),
                new RoomRegistry(properties), failOpen, Clock.systemUTC(), events);
    }

    /** Admits everyone immediately. */
    private static final class AdmittingStore implements WaitingRoom {
        @Override
        public CompletionStage<QueueStatus> enter(String room, String visitorId, AdmissionPolicy policy) {
            return CompletableFuture.completedFuture(QueueStatus.admitted(0, Instant.now().plus(policy.tokenTtl())));
        }

        @Override
        public CompletionStage<QueueStatus> poll(String room, String visitorId, AdmissionPolicy policy) {
            return enter(room, visitorId, policy);
        }

        @Override
        public CompletionStage<Void> leave(String room, String visitorId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<RoomStats> stats(String room, AdmissionPolicy policy) {
            return CompletableFuture.completedFuture(new RoomStats(0, 0));
        }
    }

    /** Every operation completes exceptionally with the configured error. */
    private static final class FailingStore implements WaitingRoom {
        private final Throwable error;
        final AtomicInteger calls = new AtomicInteger();

        FailingStore(Throwable error) {
            this.error = error;
        }

        private <T> CompletionStage<T> fail() {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(error);
        }

        @Override
        public CompletionStage<QueueStatus> enter(String room, String visitorId, AdmissionPolicy policy) {
            return fail();
        }

        @Override
        public CompletionStage<QueueStatus> poll(String room, String visitorId, AdmissionPolicy policy) {
            return fail();
        }

        @Override
        public CompletionStage<Void> leave(String room, String visitorId) {
            return fail();
        }

        @Override
        public CompletionStage<RoomStats> stats(String room, AdmissionPolicy policy) {
            return fail();
        }
    }
}
