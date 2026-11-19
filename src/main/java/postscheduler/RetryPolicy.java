package postscheduler;

import java.time.Duration;

/**
 * Exponential backoff: after the n-th failed attempt, wait {@code baseDelay * 2^(n-1)}, never more than
 * {@code maxDelay}. A post gets at most {@code maxAttempts} attempts in total.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (baseDelay.isNegative() || baseDelay.isZero() || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("need 0 < baseDelay <= maxDelay");
        }
    }

    /** Delay before the next attempt, given how many attempts have failed so far (1 or more). */
    public Duration delayAfter(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts must be at least 1");
        }
        // Past 2^30 the multiplication could overflow; any real cap is far below that anyway.
        if (failedAttempts > 31) {
            return maxDelay;
        }
        Duration delay = baseDelay.multipliedBy(1L << (failedAttempts - 1));
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    /** True when a post that has now failed {@code failedAttempts} times should not be tried again. */
    public boolean exhausted(int failedAttempts) {
        return failedAttempts >= maxAttempts;
    }
}
