package dev.foyer.spring;

import dev.foyer.core.QueueState;

/** Emitted by {@link FoyerService} for metrics; no-op consumer when Micrometer is absent. */
public record FoyerEvent(Kind kind, String room, QueueState state, boolean failOpen) {
    public enum Kind { ENTER, POLL, ADMIT }
}
