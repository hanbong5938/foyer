package dev.foyer.core;

public enum QueueState {
    /** In line, not yet allowed through. */
    WAITING,
    /** Allowed through; an {@link AdmissionToken} can be issued. */
    ADMITTED,
    /** Unknown visitor (never entered, left, or evicted after {@code queueTtl}). */
    NOT_IN_QUEUE
}
