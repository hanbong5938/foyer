package dev.foyer.spring.web;

import dev.foyer.core.QueueStatus;
import dev.foyer.spring.FoyerService;

/**
 * Wire format of the queue endpoints.
 *
 * @param state          WAITING | ADMITTED | NOT_IN_QUEUE
 * @param position       0-based place in line, -1 unless waiting
 * @param queueSize      visitors currently waiting
 * @param retryAfterMs   when waiting: how long the client should wait before polling again
 * @param admittedUntil  when admitted: epoch millis the admission expires
 * @param token          when admitted: the admission token (also set as a cookie)
 * @param failOpen       true when admitted because the queue store was unreachable
 */
public record StatusResponse(
        String state, long position, long queueSize, Long retryAfterMs, Long admittedUntil, String token,
        boolean failOpen) {

    public static StatusResponse of(FoyerService.Outcome outcome) {
        QueueStatus s = outcome.status();
        return new StatusResponse(
                s.state().name(),
                s.position(),
                s.queueSize(),
                s.retryAfter() == null ? null : s.retryAfter().toMillis(),
                s.admittedUntil() == null ? null : s.admittedUntil().toEpochMilli(),
                outcome.token(),
                outcome.failOpen());
    }

    public static StatusResponse notInQueue() {
        return new StatusResponse("NOT_IN_QUEUE", -1, -1, null, null, null, false);
    }
}
