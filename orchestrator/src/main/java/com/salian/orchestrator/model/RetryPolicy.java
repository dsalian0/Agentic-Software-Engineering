package com.salian.orchestrator.model;

/**
 * Bounded retry for RETRYABLE failures only.
 *
 * @param maxAttempts total attempts including the first (1 = no retry)
 * @param backoffMs   delay before the first retry
 * @param multiplier  exponential factor applied to each further retry
 */
public record RetryPolicy(int maxAttempts, long backoffMs, double multiplier) {

    public static final RetryPolicy NONE = new RetryPolicy(1, 0, 1.0);

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
        if (backoffMs < 0) {
            throw new IllegalArgumentException("backoffMs must be >= 0");
        }
        if (multiplier < 1.0) {
            multiplier = 1.0;
        }
    }

    public long delayBeforeAttempt(int nextAttempt) {
        return (long) (backoffMs * Math.pow(multiplier, Math.max(0, nextAttempt - 2)));
    }
}
