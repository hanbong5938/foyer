package dev.foyer.spring;

import java.util.function.Consumer;

/** Receives {@link FoyerEvent}s from {@link FoyerService}. The starter binds Micrometer when present. */
@FunctionalInterface
public interface FoyerEventListener extends Consumer<FoyerEvent> {
    FoyerEventListener NONE = event -> {};
}
