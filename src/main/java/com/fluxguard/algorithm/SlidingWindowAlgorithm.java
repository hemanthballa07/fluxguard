package com.fluxguard.algorithm;

import com.fluxguard.model.RateLimitDecision;
import java.util.List;

/**
 * Sliding-window-counter rate-limiting algorithm — Cloudflare two-counter variant.
 *
 * <p>Two Redis {@code INCR} counters track requests in the current and immediately
 * preceding fixed window. A weighted sum gives a rolling approximation of request
 * volume over the past {@code windowMs} milliseconds:
 * <pre>
 *   position  = nowMs mod windowMs
 *   weight    = 1 − position / windowMs
 *   estimated = previousCount × weight + currentCount
 *   allow if  estimated + 1 ≤ limit
 * </pre>
 *
 * <p>Maximum over-count error ≈ 1 request per window (well under 0.01 % for
 * typical limits such as 100 req/min).
 *
 * <p>This class serves two roles:
 * <ol>
 *   <li><b>Production:</b> implements {@link RateLimitAlgorithm} so that
 *       {@code RateLimitFilter} can delegate to the Redis Lua script.</li>
 *   <li><b>Testing:</b> exposes {@link #evaluate} — a pure-Java mirror of the Lua
 *       logic — so boundary conditions can be verified without Redis.</li>
 * </ol>
 */
public final class SlidingWindowAlgorithm implements RateLimitAlgorithm {

    /** Lua script resource name (without the {@code .lua} extension). */
    private static final String LUA_SCRIPT_NAME = "sliding_window";

    /** Redis key namespace that prefixes all sliding-window counter keys. */
    private static final String KEY_PREFIX = "sw:";

    /** Separator between the bucket key and the window index in a Redis key. */
    private static final String KEY_SEPARATOR = ":";

    /** Index of the "allowed" element in the Lua result list (0-based). */
    private static final int IDX_ALLOWED = 0;

    /** Index of the "remaining" element in the Lua result list (0-based). */
    private static final int IDX_REMAINING = 1;

    /** Index of the "reset after ms" element in the Lua result list (0-based). */
    private static final int IDX_RESET_AFTER = 2;

    /** Lua integer value that signals the request was allowed. */
    private static final long LUA_ALLOWED = 1L;

    private final long limit;
    private final long windowMs;

    /**
     * Constructs a sliding-window algorithm with the given parameters.
     *
     * @param limit    maximum requests allowed per window (positive)
     * @param windowMs window duration in milliseconds (positive)
     */
    public SlidingWindowAlgorithm(final long limit, final long windowMs) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got: " + limit);
        }
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be positive, got: " + windowMs);
        }
        this.limit = limit;
        this.windowMs = windowMs;
    }

    /**
     * Pure-Java evaluation of the two-counter sliding-window logic.
     *
     * <p>This method is <em>stateless</em>: it derives a decision from the supplied
     * counter values without performing any I/O. It mirrors the Redis Lua script so
     * that boundary conditions can be unit-tested without Redis.
     *
     * @param previousCount requests counted in the immediately preceding window
     * @param currentCount  requests counted in the current window (before this request)
     * @param nowMs         current epoch time in milliseconds
     * @return the rate-limit decision for this request
     */
    public RateLimitDecision evaluate(
            final long previousCount,
            final long currentCount,
            final long nowMs) {
        final long positionMs = nowMs % windowMs;
        final double weight = computeWeight(positionMs);
        final double estimated = computeEstimated(previousCount, weight, currentCount);
        if (estimated + 1.0 <= limit) {
            return buildAllow(estimated);
        }
        return buildDeny(positionMs);
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
     * <p>Returns two keys:
     * <ol>
     *   <li>{@code "sw:{bucketKey}:{windowIndex}"} — the current window counter</li>
     *   <li>{@code "sw:{bucketKey}:{windowIndex−1}"} — the previous window counter</li>
     * </ol>
     * The window index is {@code floor(nowMillis / windowMs)}, which rotates
     * automatically at each window boundary without any explicit key deletion.
     */
    @Override
    public List<String> buildLuaKeys(final String bucketKey, final long nowMillis) {
        final long windowIndex = nowMillis / windowMs;
        final String currentKey = KEY_PREFIX + bucketKey + KEY_SEPARATOR + windowIndex;
        final String previousKey = KEY_PREFIX + bucketKey + KEY_SEPARATOR + (windowIndex - 1);
        return List.of(currentKey, previousKey);
    }

    /**
     * {@inheritDoc}
     *
     * <p>For the sliding window, ARGV carries:
     * {@code limit, windowMs, nowMillis} (3 elements).
     */
    @Override
    public List<String> buildLuaArgs(final long nowMillis) {
        return List.of(
            String.valueOf(limit),
            String.valueOf(windowMs),
            String.valueOf(nowMillis)
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Expected Lua return format: {@code {allowed, remaining, resetAfterMs}}
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
     * Returns the configured request limit per window.
     *
     * @return maximum requests allowed per window (positive)
     */
    public long limit() {
        return limit;
    }

    /**
     * Returns the configured window duration.
     *
     * @return window size in milliseconds (positive)
     */
    public long windowMs() {
        return windowMs;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private double computeWeight(final long positionMs) {
        return 1.0 - (double) positionMs / windowMs;
    }

    private double computeEstimated(
            final long previousCount,
            final double weight,
            final long currentCount) {
        return previousCount * weight + currentCount;
    }

    private RateLimitDecision buildAllow(final double estimated) {
        final long remaining = (long) Math.floor(limit - estimated - 1.0);
        return RateLimitDecision.allow(remaining);
    }

    private RateLimitDecision buildDeny(final long positionMs) {
        final long resetMs = (long) Math.ceil(windowMs - positionMs);
        return RateLimitDecision.deny(resetMs);
    }
}
