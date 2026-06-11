package com.fluxguard.config;

import com.fluxguard.algorithm.RateLimitAlgorithm;
import com.fluxguard.algorithm.SlidingWindowAlgorithm;
import com.fluxguard.algorithm.TokenBucketAlgorithm;

/**
 * Immutable configuration binding an endpoint pattern to a rate-limiting algorithm.
 *
 * <p>Each instance describes how requests to {@code endpointPattern} should be
 * rate-limited. Use the static factory methods to construct instances for the
 * two supported algorithms:
 * <ul>
 *   <li>{@link #tokenBucket(String, long, long)} — for endpoints that benefit from
 *       burst tolerance (e.g. upload, ingest).</li>
 *   <li>{@link #slidingWindow(String, long, long)} — for endpoints that require
 *       strict per-window quotas (e.g. search, read-heavy APIs).</li>
 * </ul>
 *
 * <p>Dynamic reconfiguration (loading limits from a config store at runtime) is
 * planned for Month 3. Until then, configs are constructed statically in
 * application initialisation code.
 *
 * @param endpointPattern exact HTTP path or prefix this limit applies to
 * @param algorithm       the {@link RateLimitAlgorithm} instance configured for the endpoint
 */
public record LimitConfig(String endpointPattern, RateLimitAlgorithm algorithm) {

    /**
     * Creates a {@link LimitConfig} that applies a token-bucket algorithm to the
     * specified endpoint.
     *
     * @param endpointPattern     HTTP path this limit applies to
     * @param capacity            maximum tokens the bucket can hold (positive)
     * @param refillRatePerSecond tokens added per second (positive)
     * @return a fully configured {@code LimitConfig}
     */
    public static LimitConfig tokenBucket(
            final String endpointPattern,
            final long capacity,
            final long refillRatePerSecond) {
        if (endpointPattern == null || endpointPattern.isBlank()) {
            throw new IllegalArgumentException("endpointPattern must not be null or blank");
        }
        return new LimitConfig(endpointPattern,
            new TokenBucketAlgorithm(capacity, refillRatePerSecond));
    }

    /**
     * Creates a {@link LimitConfig} that applies a sliding-window-counter algorithm
     * to the specified endpoint.
     *
     * @param endpointPattern HTTP path this limit applies to
     * @param limit           maximum requests allowed per window (positive)
     * @param windowMs        window duration in milliseconds (positive)
     * @return a fully configured {@code LimitConfig}
     */
    public static LimitConfig slidingWindow(
            final String endpointPattern,
            final long limit,
            final long windowMs) {
        if (endpointPattern == null || endpointPattern.isBlank()) {
            throw new IllegalArgumentException("endpointPattern must not be null or blank");
        }
        return new LimitConfig(endpointPattern,
            new SlidingWindowAlgorithm(limit, windowMs));
    }
}
