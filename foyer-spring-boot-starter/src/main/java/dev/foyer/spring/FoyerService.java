package dev.foyer.spring;

import dev.foyer.core.AdmissionPolicy;
import dev.foyer.core.AdmissionToken;
import dev.foyer.core.AdmissionTokenCodec;
import dev.foyer.core.QueueStatus;
import dev.foyer.core.WaitingRoom;
import dev.foyer.core.WaitingRoomUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Framework-neutral queue operations: resolves the room policy, issues tokens on admission and applies the
 * fail-open/fail-closed choice when the store is down. The MVC, WebFlux and Gateway layers are thin adapters
 * over this class.
 */
public final class FoyerService {

    private static final Log log = LogFactory.getLog(FoyerService.class);
    private static final long FAIL_OPEN_LOG_INTERVAL_MS = 10_000;

    /**
     * @param status   queue standing
     * @param token    encoded admission token when {@code status} is admitted, else {@code null}
     * @param failOpen {@code true} when this admission was granted because the store was unreachable
     */
    public record Outcome(QueueStatus status, String token, boolean failOpen) {}

    private final WaitingRoom waitingRoom;
    private final AdmissionTokenCodec codec;
    private final RoomRegistry rooms;
    private final boolean failOpen;
    private final Clock clock;
    private final Consumer<FoyerEvent> events;
    private final AtomicLong lastFailOpenLog = new AtomicLong();

    public FoyerService(
            WaitingRoom waitingRoom,
            AdmissionTokenCodec codec,
            RoomRegistry rooms,
            boolean failOpen,
            Clock clock,
            Consumer<FoyerEvent> events) {
        this.waitingRoom = waitingRoom;
        this.codec = codec;
        this.rooms = rooms;
        this.failOpen = failOpen;
        this.clock = clock;
        this.events = events;
    }

    public RoomRegistry rooms() {
        return rooms;
    }

    public CompletionStage<Outcome> enter(String room, String visitorId) {
        AdmissionPolicy policy = rooms.require(room);
        return finish(room, visitorId, policy, waitingRoom.enter(room, visitorId, policy), FoyerEvent.Kind.ENTER);
    }

    public CompletionStage<Outcome> poll(String room, String visitorId) {
        AdmissionPolicy policy = rooms.require(room);
        return finish(room, visitorId, policy, waitingRoom.poll(room, visitorId, policy), FoyerEvent.Kind.POLL);
    }

    /** Fail-open tolerates an unreachable store here too; any other failure propagates in both modes. */
    public CompletionStage<Void> leave(String room, String visitorId) {
        rooms.require(room);
        return waitingRoom.leave(room, visitorId).exceptionally(error -> {
            Throwable cause = unwrap(error);
            if (cause instanceof WaitingRoomUnavailableException && failOpen) {
                logFailOpen(room, cause, true);
                return null;
            }
            throw rethrow(cause);
        });
    }

    private CompletionStage<Outcome> finish(
            String room, String visitorId, AdmissionPolicy policy, CompletionStage<QueueStatus> stage,
            FoyerEvent.Kind kind) {
        return stage.handle((status, error) -> {
            if (error == null) {
                publish(new FoyerEvent(kind, room, status.state(), false));
                if (status.isAdmitted()) {
                    publish(new FoyerEvent(FoyerEvent.Kind.ADMIT, room, status.state(), false));
                    return new Outcome(status, issue(room, visitorId, status.admittedUntil()), false);
                }
                return new Outcome(status, null, false);
            }
            Throwable cause = unwrap(error);
            if (!(cause instanceof WaitingRoomUnavailableException) || !failOpen) {
                throw rethrow(cause);
            }
            logFailOpen(room, cause, false);
            Instant until = clock.instant().plus(policy.tokenTtl());
            QueueStatus admitted = QueueStatus.admitted(0, until);
            publish(new FoyerEvent(FoyerEvent.Kind.ADMIT, room, admitted.state(), true));
            return new Outcome(admitted, issue(room, visitorId, until), true);
        });
    }

    /** Listeners observe; a throwing listener must not turn an admission the store already granted into an error. */
    private void publish(FoyerEvent event) {
        try {
            events.accept(event);
        } catch (RuntimeException e) {
            log.warn("foyer: event listener failed for " + event, e);
        }
    }

    private String issue(String room, String visitorId, Instant until) {
        return codec.encode(new AdmissionToken(room, visitorId, clock.instant(), until));
    }

    private void logFailOpen(String room, Throwable cause, boolean leaving) {
        long now = clock.millis();
        long last = lastFailOpenLog.get();
        if (now - last >= FAIL_OPEN_LOG_INTERVAL_MS && lastFailOpenLog.compareAndSet(last, now)) {
            String consequence = leaving ? "ignoring leave for room '" + room + "'"
                    : "admitting visitors to room '" + room + "' without queueing";
            log.warn("foyer: queue store unavailable, " + consequence
                    + " (foyer.fail-open=true): " + cause);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        return new CompletionException(t);
    }
}
