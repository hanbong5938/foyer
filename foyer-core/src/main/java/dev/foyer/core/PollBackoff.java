package dev.foyer.core;

import java.time.Duration;

/**
 * Server-driven poll interval. Far-back visitors poll rarely, near-front visitors poll often, so poll load is
 * proportional to admission rate rather than to queue length.
 */
public final class PollBackoff {

    private final Duration min;
    private final Duration max;

    public PollBackoff(Duration min, Duration max) {
        if (min.isNegative() || min.isZero()) throw new IllegalArgumentException("min must be > 0");
        if (max.compareTo(min) < 0) throw new IllegalArgumentException("max must be >= min");
        this.min = min;
        this.max = max;
    }

    public static PollBackoff defaults() {
        return new PollBackoff(Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    /** Half the estimated time-to-front, clamped to {@code [min, max]}. */
    public Duration retryAfter(long position, AdmissionPolicy policy) {
        double intervalsToFront = (double) (position + 1) / policy.rate();
        long estimateMs = (long) (intervalsToFront * policy.interval().toMillis());
        long half = estimateMs / 2;
        long clamped = Math.max(min.toMillis(), Math.min(max.toMillis(), half));
        return Duration.ofMillis(clamped);
    }
}
