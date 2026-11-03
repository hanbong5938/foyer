package dev.foyer.core;

import java.util.concurrent.CompletionStage;

/**
 * The queue port. Implementations must make {@link #enter} and {@link #poll} atomic per room: two concurrent
 * polls must never both consume the same admission slot, and a visitor that was admitted must keep reporting
 * {@link QueueState#ADMITTED} until {@code tokenTtl} elapses even if the caller crashed before reading the
 * response.
 *
 * <p>Every method is async so the same implementation serves servlet (join) and reactive (adapt) callers.
 * Failures complete exceptionally with {@link WaitingRoomUnavailableException} when the backing store cannot
 * be reached; callers decide whether to fail open or closed.
 */
public interface WaitingRoom {

    /**
     * Joins the line if not already in it, then attempts admission. Immediate admission requires available
     * token budget and capacity under {@code maxActive}, not merely an empty line.
     */
    CompletionStage<QueueStatus> enter(String room, String visitorId, AdmissionPolicy policy);

    /**
     * Refreshes the visitor's place and attempts admission. Returns {@link QueueState#NOT_IN_QUEUE} for unknown
     * visitors instead of enrolling them.
     */
    CompletionStage<QueueStatus> poll(String room, String visitorId, AdmissionPolicy policy);

    /** Removes the visitor from the line (no-op if absent or already admitted). */
    CompletionStage<Void> leave(String room, String visitorId);

    /** Counts unexpired waiting positions and admissions using the room's current policy. */
    CompletionStage<RoomStats> stats(String room, AdmissionPolicy policy);
}
