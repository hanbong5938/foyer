package dev.foyer.core;

import java.time.Duration;
import java.time.Instant;

/**
 * Snapshot of one visitor's standing in a room.
 *
 * @param state         where the visitor is
 * @param position      0-based place in line; {@code -1} unless {@link QueueState#WAITING}
 * @param queueSize     number of visitors currently waiting (after this call)
 * @param admittedUntil when the admission expires; {@code null} unless {@link QueueState#ADMITTED}
 * @param retryAfter    suggested delay before the next poll; {@code null} unless {@link QueueState#WAITING}
 */
public record QueueStatus(
        QueueState state, long position, long queueSize, Instant admittedUntil, Duration retryAfter) {

    public static QueueStatus waiting(long position, long queueSize, Duration retryAfter) {
        return new QueueStatus(QueueState.WAITING, position, queueSize, null, retryAfter);
    }

    public static QueueStatus admitted(long queueSize, Instant admittedUntil) {
        return new QueueStatus(QueueState.ADMITTED, -1, queueSize, admittedUntil, null);
    }

    public static QueueStatus notInQueue(long queueSize) {
        return new QueueStatus(QueueState.NOT_IN_QUEUE, -1, queueSize, null, null);
    }

    public boolean isAdmitted() {
        return state == QueueState.ADMITTED;
    }
}
