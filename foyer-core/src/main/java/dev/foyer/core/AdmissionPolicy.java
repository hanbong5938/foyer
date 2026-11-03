package dev.foyer.core;

import java.time.Duration;

/**
 * How a room lets visitors through.
 *
 * <p>Admission is a token bucket: every {@code interval} the bucket gains {@code rate} tokens, capped at
 * {@code burst}. A waiting visitor is admitted when their position is inside the bucket (and inside the free
 * {@code maxActive} capacity if set). Admission happens lazily on the visitor's own poll, so there is no
 * scheduler and nothing to elect a leader for.
 *
 * @param rate      admissions gained per {@code interval}
 * @param interval  refill period
 * @param burst     bucket capacity; how many can enter at once after an idle period (defaults to {@code rate})
 * @param maxActive hard cap on concurrently admitted (unexpired) visitors; {@code 0} = unlimited
 * @param tokenTtl  how long an admission stays valid
 * @param queueTtl  waiters that have not polled for this long are dropped so they stop blocking the line
 */
public record AdmissionPolicy(
        int rate, Duration interval, int burst, int maxActive, Duration tokenTtl, Duration queueTtl) {

    public AdmissionPolicy {
        if (rate < 1) throw new IllegalArgumentException("rate must be >= 1");
        requireMillis("interval", interval, false);
        if (burst < 1) throw new IllegalArgumentException("burst must be >= 1");
        if (maxActive < 0) throw new IllegalArgumentException("maxActive must be >= 0");
        requireMillis("tokenTtl", tokenTtl, false);
        requireMillis("queueTtl", queueTtl, true);
    }

    /**
     * Durations reach the store as whole milliseconds ({@link Duration#toMillis()}), so anything the store would
     * truncate to zero or fail to represent is rejected here rather than silently disabling a timeout.
     */
    private static void requireMillis(String name, Duration value, boolean allowZero) {
        String expected = allowZero
                ? name + " must be zero or a positive whole-millisecond duration representable as a long"
                : name + " must be a positive whole-millisecond duration representable as a long";
        if (value == null || value.isNegative() || (value.isZero() && !allowZero)
                || value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(expected);
        }
        try {
            value.toMillis();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(expected, overflow);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int rate = 10;
        private Duration interval = Duration.ofSeconds(1);
        private Integer burst;
        private int maxActive = 0;
        private Duration tokenTtl = Duration.ofMinutes(5);
        private Duration queueTtl = Duration.ofSeconds(30);

        public Builder rate(int rate) { this.rate = rate; return this; }
        public Builder interval(Duration interval) { this.interval = interval; return this; }
        public Builder burst(int burst) { this.burst = burst; return this; }
        public Builder maxActive(int maxActive) { this.maxActive = maxActive; return this; }
        public Builder tokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; return this; }
        public Builder queueTtl(Duration queueTtl) { this.queueTtl = queueTtl; return this; }

        public AdmissionPolicy build() {
            return new AdmissionPolicy(rate, interval, burst == null ? rate : burst, maxActive, tokenTtl, queueTtl);
        }
    }
}
