package com.fluxguard.algorithm;

import com.fluxguard.model.RateLimitDecision;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SlidingWindowAlgorithm}.
 *
 * <p>All tests are pure Java — no Spring context, no Redis.
 * They exercise {@link SlidingWindowAlgorithm#evaluate} directly to verify
 * the weighted-interpolation maths at boundary conditions: exact limit, limit+1,
 * burst, window rollover, and full / partial previous-window weight.
 *
 * <p>Convention: limit=10, windowMs=60 000 ms (1 minute) unless otherwise noted.
 */
class SlidingWindowAlgorithmTest {

    private static final long LIMIT = 10L;
    private static final long WINDOW_MS = 60_000L;
    private static final long BASE_TIME_MS = 1_000_000L;
    private static final String BUCKET_KEY = "test-client:/api/test";

    // ── Derived constants (no magic literals in test bodies) ──────────────────

    /** Time at the very start of a window (positionMs = 0). */
    private static final long WINDOW_START_MS = BASE_TIME_MS - (BASE_TIME_MS % WINDOW_MS);

    /** Time at the exact midpoint of a window (positionMs = WINDOW_MS / 2). */
    private static final long WINDOW_MID_MS = WINDOW_START_MS + WINDOW_MS / 2;

    /** Time one millisecond before the next window boundary (positionMs = WINDOW_MS - 1). */
    private static final long WINDOW_END_MS = WINDOW_START_MS + WINDOW_MS - 1;

    /** Number of ARGV elements the sliding-window Lua script expects. */
    private static final int LUA_ARGS_COUNT = 3;

    /** Number of KEYS the sliding-window Lua script expects. */
    private static final int LUA_KEYS_COUNT = 2;

    /** Remaining value in the allowed parseResult fixture. */
    private static final long PARSE_RESULT_REMAINING = 5L;

    /** Reset-after value in the denied parseResult fixture. */
    private static final long PARSE_RESULT_RESET_MS = 500L;

    private SlidingWindowAlgorithm algorithm;

    /** Fresh algorithm instance before each test. */
    @BeforeEach
    void setUp() {
        algorithm = new SlidingWindowAlgorithm(LIMIT, WINDOW_MS);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @Test
    void limitIsReturned() {
        assertEquals(LIMIT, algorithm.limit());
    }

    @Test
    void windowMsIsReturned() {
        assertEquals(WINDOW_MS, algorithm.windowMs());
    }

    @Test
    void luaScriptNameIsSlidingWindow() {
        assertEquals("sliding_window", algorithm.luaScriptName());
    }

    // ── Basic allow / deny ────────────────────────────────────────────────────

    @Test
    void emptyWindowAllowsRequest() {
        final RateLimitDecision decision = algorithm.evaluate(0L, 0L, WINDOW_START_MS);

        assertTrue(decision.allowed());
        assertEquals(LIMIT - 1, decision.remainingTokens());
        assertEquals(0L, decision.resetAfterMs());
    }

    @Test
    void fullCurrentWindowDeniesRequest() {
        // previousCount=0 so no previous weight; currentCount=LIMIT → denied
        final RateLimitDecision decision = algorithm.evaluate(0L, LIMIT, WINDOW_START_MS);

        assertFalse(decision.allowed());
        assertEquals(0L, decision.remainingTokens());
        assertTrue(decision.resetAfterMs() > 0,
            "Denied decision must carry a positive retry hint");
    }

    // ── Exact-limit boundary (CLAUDE.md requirement) ──────────────────────────

    @Test
    void exactlyAtLimitMinusOneAllows() {
        // estimated = 0 + (LIMIT - 1) = LIMIT - 1; LIMIT - 1 + 1 = LIMIT ≤ LIMIT → allow
        final RateLimitDecision decision = algorithm.evaluate(0L, LIMIT - 1, WINDOW_START_MS);

        assertTrue(decision.allowed());
        assertEquals(0L, decision.remainingTokens());
    }

    @Test
    void exactlyAtLimitDenies() {
        // estimated = 0 + LIMIT = LIMIT; LIMIT + 1 > LIMIT → deny
        final RateLimitDecision decision = algorithm.evaluate(0L, LIMIT, WINDOW_START_MS);

        assertFalse(decision.allowed());
    }

    // ── Limit+1 (CLAUDE.md requirement) ──────────────────────────────────────

    @Test
    void limitPlusOneDenies() {
        final RateLimitDecision decision = algorithm.evaluate(0L, LIMIT + 1, WINDOW_START_MS);

        assertFalse(decision.allowed(), "Request beyond limit must be denied");
    }

    // ── Burst: sequential requests draining a fresh window ────────────────────

    @Test
    void burstOfLimitRequestsAllAllowed() {
        long currentCount = 0L;
        int allowedCount = 0;

        for (long i = 0; i < LIMIT; i++) {
            final RateLimitDecision d = algorithm.evaluate(0L, currentCount, WINDOW_START_MS);
            if (d.allowed()) {
                allowedCount++;
                currentCount++;
            }
        }

        assertEquals((int) LIMIT, allowedCount,
            "All burst requests within limit must be allowed");
    }

    // ── Weighted interpolation (previous window) ──────────────────────────────

    @Test
    void startOfWindowGivesFullPreviousWeight() {
        // positionMs=0, weight=1.0 → estimated = LIMIT * 1.0 + 0 = LIMIT → deny
        final RateLimitDecision decision = algorithm.evaluate(LIMIT, 0L, WINDOW_START_MS);

        assertFalse(decision.allowed(),
            "Full previous window at window start must count entirely toward limit");
    }

    @Test
    void midWindowHalfWeightOnPrevious() {
        // positionMs=WINDOW_MS/2, weight=0.5
        // estimated = LIMIT * 0.5 + 0 = LIMIT/2 < LIMIT → allow (for LIMIT >= 2)
        final RateLimitDecision decision = algorithm.evaluate(LIMIT, 0L, WINDOW_MID_MS);

        assertTrue(decision.allowed(),
            "Half-weight previous window below limit must be allowed");
    }

    @Test
    void endOfWindowMinimalPreviousWeight() {
        // positionMs=WINDOW_MS-1, weight≈1/WINDOW_MS ≈ 0
        // estimated ≈ 0 + 0 = 0 → allow, remaining = LIMIT - 1
        final RateLimitDecision decision = algorithm.evaluate(LIMIT, 0L, WINDOW_END_MS);

        assertTrue(decision.allowed(),
            "Near-zero previous-window weight should not block a fresh window");
    }

    // ── Reset hint ────────────────────────────────────────────────────────────

    @Test
    void resetHintIsPositiveWhenDenied() {
        final RateLimitDecision decision = algorithm.evaluate(0L, LIMIT, WINDOW_START_MS);

        assertFalse(decision.allowed());
        assertTrue(decision.resetAfterMs() > 0,
            "Denied decision must tell the caller how long to wait");
    }

    @Test
    void resetHintIsZeroWhenAllowed() {
        final RateLimitDecision decision = algorithm.evaluate(0L, 0L, WINDOW_START_MS);

        assertTrue(decision.allowed());
        assertEquals(0L, decision.resetAfterMs());
    }

    // ── parseResult ───────────────────────────────────────────────────────────

    @Test
    void parseResultAllowed() {
        final List<Object> luaResult = List.of(1L, PARSE_RESULT_REMAINING, 0L);
        final RateLimitDecision decision = algorithm.parseResult(luaResult);

        assertTrue(decision.allowed());
        assertEquals(PARSE_RESULT_REMAINING, decision.remainingTokens());
        assertEquals(0L, decision.resetAfterMs());
    }

    @Test
    void parseResultDenied() {
        final List<Object> luaResult = List.of(0L, 0L, PARSE_RESULT_RESET_MS);
        final RateLimitDecision decision = algorithm.parseResult(luaResult);

        assertFalse(decision.allowed());
        assertEquals(0L, decision.remainingTokens());
        assertEquals(PARSE_RESULT_RESET_MS, decision.resetAfterMs());
    }

    // ── buildLuaArgs ──────────────────────────────────────────────────────────

    @Test
    void buildLuaArgsHasThreeElements() {
        final List<String> args = algorithm.buildLuaArgs(BASE_TIME_MS);
        assertEquals(LUA_ARGS_COUNT, args.size());
    }

    @Test
    void buildLuaArgsCarriesLimitWindowMsAndNow() {
        final List<String> args = algorithm.buildLuaArgs(BASE_TIME_MS);
        assertEquals(String.valueOf(LIMIT), args.get(0));
        assertEquals(String.valueOf(WINDOW_MS), args.get(1));
        assertEquals(String.valueOf(BASE_TIME_MS), args.get(2));
    }

    // ── buildLuaKeys ──────────────────────────────────────────────────────────

    @Test
    void buildLuaKeysHasTwoElements() {
        final List<String> keys = algorithm.buildLuaKeys(BUCKET_KEY, BASE_TIME_MS);
        assertEquals(LUA_KEYS_COUNT, keys.size());
    }

    @Test
    void buildLuaKeysCurrentAndPreviousAreDifferent() {
        final List<String> keys = algorithm.buildLuaKeys(BUCKET_KEY, BASE_TIME_MS);
        assertNotEquals(keys.get(0), keys.get(1),
            "Current and previous window keys must be distinct");
    }

    @Test
    void buildLuaKeysRotateAcrossWindowBoundary() {
        final long beforeBoundary = WINDOW_START_MS - 1;
        final List<String> keysBefore = algorithm.buildLuaKeys(BUCKET_KEY, beforeBoundary);
        final List<String> keysAfter = algorithm.buildLuaKeys(BUCKET_KEY, WINDOW_START_MS);

        // After crossing the boundary, the old "current" becomes the new "previous"
        assertEquals(keysBefore.get(0), keysAfter.get(1),
            "Old current key must become new previous key after window rotation");
        assertNotEquals(keysBefore.get(0), keysAfter.get(0),
            "Current key must change across a window boundary");
    }
}
