package com.fluxguard.model;

import com.fluxguard.config.LimitConfig;

/**
 * Immutable feature flag binding an endpoint to an optional override algorithm.
 *
 * <p>A flag can operate in two modes:
 * <ul>
 *   <li><b>Live rollout</b> ({@code darkLaunch=false}): clients in the rollout use
 *       {@code overrideConfig} for real rate-limit decisions.</li>
 *   <li><b>Dark launch</b> ({@code darkLaunch=true}): the override algorithm runs as
 *       a shadow; its decision is observed and metered but never returned to the caller.
 *       The primary config always governs the real response.</li>
 * </ul>
 *
 * @param endpoint       HTTP path this flag applies to (e.g. {@code /api/search})
 * @param enabled        {@code true} if the flag is active; disabled flags are ignored
 * @param darkLaunch     {@code true} to run as shadow only, {@code false} for live rollout
 * @param rolloutPercent percentage of clients (0–100) that participate in the rollout
 * @param overrideConfig the {@link LimitConfig} used when the flag is active and in rollout
 */
public record FeatureFlag(
        String endpoint,
        boolean enabled,
        boolean darkLaunch,
        int rolloutPercent,
        LimitConfig overrideConfig) {
}
