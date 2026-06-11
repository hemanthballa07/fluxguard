package com.fluxguard.config;

import java.util.Map;
import java.util.Optional;

/**
 * Provides runtime access to per-endpoint rate-limit configuration.
 *
 * <p>Implementations are responsible for persisting and retrieving
 * {@link LimitConfig} entries so that {@code RateLimitFilter} can
 * apply the correct algorithm per request without a service restart.
 *
 * <p>See ADR-004 for the storage strategy decision.
 */
public interface ConfigService {

    /**
     * Returns the {@link LimitConfig} for the given endpoint, or empty if not configured.
     *
     * @param endpoint exact HTTP request URI (e.g. {@code /api/search})
     * @return configured limit, or {@link Optional#empty()} if absent or unreadable
     */
    Optional<LimitConfig> getConfig(String endpoint);

    /**
     * Returns a snapshot of all currently configured endpoint-to-limit mappings.
     *
     * <p>Malformed entries are excluded and logged at WARN level.
     *
     * @return immutable view of all readable configs; never null
     */
    Map<String, LimitConfig> getAllConfigs();

    /**
     * Creates or replaces the {@link LimitConfig} for the given endpoint.
     *
     * @param endpoint exact HTTP request URI
     * @param config   limit config to store
     */
    void putConfig(String endpoint, LimitConfig config);

    /**
     * Removes the {@link LimitConfig} for the given endpoint.
     *
     * <p>No-op if the endpoint is not present.
     *
     * @param endpoint exact HTTP request URI
     */
    void removeConfig(String endpoint);

    /**
     * Returns {@code true} if the global kill switch is active.
     *
     * <p>When active, {@code RateLimitFilter} allows all requests through
     * without consulting any per-endpoint config.
     *
     * @return {@code true} if rate limiting is globally disabled
     */
    boolean isKillSwitchActive();

    /**
     * Activates or deactivates the global kill switch.
     *
     * @param active {@code true} to disable rate limiting globally, {@code false} to re-enable
     */
    void setKillSwitch(boolean active);
}
