package dev.foyer.core;

/**
 * @param waiting number of unexpired waiting positions; every waiter counts when {@code queueTtl} is zero
 * @param active  number of admitted visitors whose admission has not expired
 */
public record RoomStats(long waiting, long active) {}
