package com.fluxguard.model;

/**
 * Immutable result of a single rate-limit evaluation.
 *
 * @param allowed         {@code true} when the request is permitted to proceed
 * @param remainingTokens tokens (or request slots) remaining in the current
 *                        window after this decision; always {@code 0} when denied
 * @param resetAfterMs    milliseconds until at least one token becomes available;
 *                        {@code 0} when the request is allowed
 */
public record RateLimitDecision(boolean allowed, long remainingTokens, long resetAfterMs) {

    /**
     * Builds an allowed decision with no retry hint.
     *
     * @param remaining tokens left after consuming one
     * @return an allowed {@link RateLimitDecision}
     */
    public static RateLimitDecision allow(final long remaining) {
        return new RateLimitDecision(true, remaining, 0L);
    }

    /**
     * Builds a denied decision with a retry hint.
     *
     * @param resetAfterMs milliseconds the caller should wait before retrying
     * @return a denied {@link RateLimitDecision}
     */
    public static RateLimitDecision deny(final long resetAfterMs) {
        return new RateLimitDecision(false, 0L, resetAfterMs);
    }
}
