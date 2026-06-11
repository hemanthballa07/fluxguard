package com.fluxguard.model;

/**
 * Request body for creating or updating a per-endpoint feature flag.
 *
 * <p>{@code algorithm} is optional. When absent (null or blank) the flag is stored with
 * no override config — {@code RateLimitFilter} will use the primary config for clients
 * in rollout. When present, the algorithm-specific capacity/rate/limit/window fields
 * are validated by {@code AdminController} and a {@link LimitConfig} override is built.
 *
 * @param enabled              {@code true} to activate the flag
 * @param darkLaunch           {@code true} to run as shadow only; {@code false} for live rollout
 * @param rolloutPercent       percentage of clients (0–100) that participate in the rollout
 * @param algorithm            optional override algorithm — {@code token_bucket} or
 *                             {@code sliding_window}; {@code null} means no override
 * @param capacity             token bucket capacity (required when algorithm is token_bucket)
 * @param refillRatePerSecond  token bucket refill rate (required when algorithm is token_bucket)
 * @param limit                sliding window request limit (required when algorithm is sliding_window)
 * @param windowMs             sliding window duration in milliseconds (required when algorithm
 *                             is sliding_window)
 */
public record FeatureFlagRequest(
        boolean enabled,
        boolean darkLaunch,
        int rolloutPercent,
        String algorithm,
        Long capacity,
        Long refillRatePerSecond,
        Long limit,
        Long windowMs) {
}
