package com.fluxguard.algorithm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fluxguard.model.RateLimitDecision;
import java.util.List;

/**
 * Token-bucket rate-limiting algorithm.
 *
 * <p>Tokens accumulate at {@code refillRatePerSecond} up to {@code capacity}.
 * Each allowed request consumes one token. Requests arriving when the bucket is
 * empty are denied with a retry hint indicating how long to wait for a token.
 *
 * <p>This class serves two roles:
 * <ol>
 *   <li><b>Production:</b> implements {@link RateLimitAlgorithm} so that
 *       {@code RateLimitFilter} can pass its parameters to the Redis Lua script.</li>
 *   <li><b>Testing:</b> exposes {@link #evaluate} — a pure-Java mirror of the Lua
 *       logic — so boundary conditions can be verified without Redis.</li>
 * </ol>
 */
public final class TokenBucketAlgorithm implements RateLimitAlgorithm {

    /** Name of the Lua script resource (without extension) stored under {@code resources/lua/}. */
    private static final String LUA_SCRIPT_NAME = "token_bucket";

    /** Milliseconds in one second; used to convert the refill rate to per-millisecond. */
    private static final long MILLIS_PER_SECOND = 1000L;

    /** Index of the "allowed" field in the Lua result list (0-based). */
    private static final int IDX_ALLOWED = 0;

    /** Index of the "remaining tokens" field in the Lua result list (0-based). */
    private static final int IDX_REMAINING = 1;

    /** Index of the "reset after ms" field in the Lua result list (0-based). */
    private static final int IDX_RESET_AFTER = 2;

    /** Lua integer value that signals the request was allowed. */
    private static final long LUA_ALLOWED = 1L;

    private final long capacity;
    private final long refillRatePerSecond;

    /**
     * Constructs a token-bucket algorithm with the given parameters.
     *
     * @param capacity            maximum number of tokens the bucket can hold (positive)
     * @param refillRatePerSecond tokens added per second (positive)
     */
    public TokenBucketAlgorithm(final long capacity, final long refillRatePerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException(
                "refillRatePerSecond must be positive, got: " + refillRatePerSecond);
        }
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
    }

    /**
     * Pure-Java evaluation of the token-bucket logic — mirrors the Redis Lua script.
     *
     * <p>The method is stateless: it derives a new bucket state from its inputs
     * and returns a decision, leaving persistence to the caller (or Lua in production).
     *
     * @param currentTokens  fractional token count before this request
     * @param lastRefillMs   epoch milliseconds of the last recorded refill
     * @param nowMs          current epoch milliseconds
     * @return the rate-limit decision for this request
     */
    public RateLimitDecision evaluate(
            final double currentTokens,
            final long lastRefillMs,
            final long nowMs) {
        final double tokensAfterRefill = computeRefill(currentTokens, lastRefillMs, nowMs);
        if (tokensAfterRefill >= 1.0) {
            return buildAllowDecision(tokensAfterRefill);
        }
        return buildDenyDecision(tokensAfterRefill);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String luaScriptName() {
        return LUA_SCRIPT_NAME;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Token bucket state is stored in a single Redis hash, so only one key
     * is returned: the raw {@code bucketKey} itself.
     */
    @Override
    public List<String> buildLuaKeys(final String bucketKey, final long nowMillis) {
        return List.of(bucketKey);
    }

    /**
     * {@inheritDoc}
     *
     * <p>For the token bucket, ARGV carries:
     * {@code capacity, refillRatePerSecond, nowMillis, tokensRequested=1}.
     */
    @Override
    public List<String> buildLuaArgs(final long nowMillis) {
        return List.of(
            String.valueOf(capacity),
            String.valueOf(refillRatePerSecond),
            String.valueOf(nowMillis),
            "1"
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Expected Lua return format: {@code {allowed, remainingTokens, resetAfterMs}}
     * where {@code allowed} is {@code 1} (permit) or {@code 0} (deny).
     */
    @Override
    public RateLimitDecision parseResult(final List<Object> luaResult) {
        final long allowed = (Long) luaResult.get(IDX_ALLOWED);
        final long remaining = (Long) luaResult.get(IDX_REMAINING);
        final long resetAfterMs = (Long) luaResult.get(IDX_RESET_AFTER);
        if (allowed == LUA_ALLOWED) {
            return RateLimitDecision.allow(remaining);
        }
        return RateLimitDecision.deny(resetAfterMs);
    }

    /**
     * Returns the configured bucket capacity.
     *
     * @return maximum tokens
     */
    @JsonProperty
    public long capacity() {
        return capacity;
    }

    /**
     * Returns the configured refill rate.
     *
     * @return tokens added per second
     */
    @JsonProperty
    public long refillRatePerSecond() {
        return refillRatePerSecond;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private double computeRefill(
            final double tokens,
            final long lastRefillMs,
            final long nowMs) {
        final long elapsedMs = nowMs - lastRefillMs;
        final double added = (double) elapsedMs * refillRatePerSecond / MILLIS_PER_SECOND;
        return Math.min(capacity, tokens + added);
    }

    private RateLimitDecision buildAllowDecision(final double tokensAfterRefill) {
        final long remaining = (long) Math.floor(tokensAfterRefill - 1.0);
        return RateLimitDecision.allow(remaining);
    }

    private RateLimitDecision buildDenyDecision(final double tokensAfterRefill) {
        final double deficit = 1.0 - tokensAfterRefill;
        final long resetMs = (long) Math.ceil(deficit / refillRatePerSecond * MILLIS_PER_SECOND);
        return RateLimitDecision.deny(resetMs);
    }
}
