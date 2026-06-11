package com.fluxguard.config;

import com.fluxguard.model.FeatureFlag;
import java.util.Map;
import java.util.Optional;

/**
 * Provides runtime access to per-endpoint feature flags.
 *
 * <p>Implementations persist and retrieve {@link FeatureFlag} entries so that
 * {@code RateLimitFilter} can apply flag-controlled algorithm overrides and
 * dark-launch shadow runs without a service restart.
 *
 * <p>See ADR-005 for the storage strategy and rollout algorithm.
 */
public interface FeatureFlagService {

    /**
     * Returns the active {@link FeatureFlag} for the given endpoint, or empty if none is stored.
     *
     * @param endpoint exact HTTP request URI (e.g. {@code /api/search})
     * @return the stored flag, or {@link Optional#empty()} if absent or unreadable
     */
    Optional<FeatureFlag> getFlagForEndpoint(String endpoint);

    /**
     * Returns {@code true} if the given client falls within the flag's rollout percentage.
     *
     * <p>The mapping is deterministic — the same {@code (flag.endpoint, clientId)} pair
     * always returns the same result regardless of when it is called.
     *
     * @param flag     the feature flag containing the rollout percentage
     * @param clientId the unique client identifier
     * @return {@code true} if the client is in rollout
     */
    boolean isClientInRollout(FeatureFlag flag, String clientId);

    /**
     * Returns a snapshot of all currently stored flags.
     *
     * <p>Malformed entries are excluded and logged at WARN level.
     *
     * @return map of endpoint path to {@link FeatureFlag}; never null
     */
    Map<String, FeatureFlag> getAllFlags();

    /**
     * Creates or replaces the {@link FeatureFlag} for the given endpoint.
     *
     * @param endpoint exact HTTP request URI
     * @param flag     flag to store
     */
    void putFlag(String endpoint, FeatureFlag flag);

    /**
     * Removes the {@link FeatureFlag} for the given endpoint.
     *
     * <p>No-op if the endpoint has no flag stored.
     *
     * @param endpoint exact HTTP request URI
     */
    void removeFlag(String endpoint);
}
