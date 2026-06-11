package com.fluxguard.util;

import java.util.Objects;

/**
 * Deterministic hash utilities for feature-flag rollout bucketing.
 *
 * <p>Assigns each {@code (endpoint, clientId)} pair to a stable bucket in
 * {@code [0, BUCKET_COUNT - 1]}. Used by {@code RedisFeatureFlagService} to
 * decide whether a client falls within a given rollout percentage.
 */
public final class HashUtil {

    /** Number of rollout buckets. Rollout percentage maps directly to bucket threshold. */
    public static final int BUCKET_COUNT = 100;

    private HashUtil() {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * Returns the rollout bucket index for the given endpoint and client ID.
     *
     * <p>The result is stable — the same inputs always yield the same bucket.
     * The distribution is uniform enough for percentage-based rollouts at typical
     * client-id cardinalities.
     *
     * @param endpoint the HTTP endpoint (e.g. {@code /api/search})
     * @param clientId the unique client identifier
     * @return a bucket index in {@code [0, BUCKET_COUNT - 1]}
     */
    public static int rolloutBucket(final String endpoint, final String clientId) {
        return Math.abs(Objects.hash(endpoint, clientId)) % BUCKET_COUNT;
    }
}
