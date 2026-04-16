package com.fluxguard.algorithm;

import com.fluxguard.model.RateLimitDecision;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TokenBucketAlgorithm}.
 *
 * <p>All tests are pure Java — no Spring context, no Redis.
 * They exercise {@link TokenBucketAlgorithm#evaluate} directly to verify the
 * token-bucket maths at boundary conditions: exact limit, limit+1, burst, and
 * partial / full refill.
 *
 * <p>Convention: capacity=10, refillRate=2 tokens/sec unless otherwise noted.
 */
class TokenBucketAlgorithmTest {

    private static final long CAPACITY = 10L;
    private static final long REFILL_RATE = 2L;
    private static final long BASE_TIME_MS = 1_000_000L;

    /** Expected remaining tokens after one consume from a 5-token bucket (5 - 1 = 4). */
    private static final long REMAINING_AFTER_FIVE_MINUS_ONE = 4L;

    /** Remaining tokens in the "allowed" parseResult fixture. */
    private static final long PARSE_RESULT_REMAINING = 9L;

    /** Reset-after hint in the "denied" parseResult fixture. */
    private static final long PARSE_RESULT_RESET_MS = 500L;

    /** Number of ARGV elements the token-bucket Lua script expects. */
    private static final int LUA_ARGS_COUNT = 4;

    private TokenBucketAlgorithm algorithm;

    /** Fresh algorithm instance before each test. */
    @BeforeEach
    void setUp() {
        algorithm = new TokenBucketAlgorithm(CAPACITY, REFILL_RATE);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @Test
    void capacityIsReturned() {
        assertEquals(CAPACITY, algorithm.capacity());
    }

    @Test
    void refillRateIsReturned() {
        assertEquals(REFILL_RATE, algorithm.refillRatePerSecond());
    }

    @Test
    void luaScriptNameIsTokenBucket() {
        assertEquals("token_bucket", algorithm.luaScriptName());
    }

    // ── Basic allow / deny ────────────────────────────────────────────────────

    @Test
    void fullBucketAllowsRequest() {
        final RateLimitDecision decision = algorithm.evaluate(CAPACITY, BASE_TIME_MS, BASE_TIME_MS);

        assertTrue(decision.allowed());
        assertEquals(CAPACITY - 1, decision.remainingTokens());
        assertEquals(0L, decision.resetAfterMs());
    }

    @Test
    void emptyBucketDeniesRequest() {
        final RateLimitDecision decision = algorithm.evaluate(0.0, BASE_TIME_MS, BASE_TIME_MS);

        assertFalse(decision.allowed());
        assertEquals(0L, decision.remainingTokens());
        assertTrue(decision.resetAfterMs() > 0,
            "Denied decision must carry a positive retry hint");
    }

    // ── Exact-limit boundary (CLAUDE.md requirement) ──────────────────────────

    @Test
    void exactlyOneTokenAllows() {
        final RateLimitDecision decision = algorithm.evaluate(1.0, BASE_TIME_MS, BASE_TIME_MS);

        assertTrue(decision.allowed());
        assertEquals(0L, decision.remainingTokens());
    }

    @Test
    void lessThanOneTokenDenies() {
        // 0.5 tokens — not enough for one request
        final RateLimitDecision decision = algorithm.evaluate(0.5, BASE_TIME_MS, BASE_TIME_MS);

        assertFalse(decision.allowed());
    }

    // ── Limit+1: exhausting the bucket then one more request ──────────────────

    @Test
    void requestAfterBucketExhaustionIsDenied() {
        // Drain all 10 tokens: only 9 remain after first request (full bucket)
        RateLimitDecision last = algorithm.evaluate(CAPACITY, BASE_TIME_MS, BASE_TIME_MS);
        double remaining = last.remainingTokens();

        // Drain until empty
        for (int i = 0; i < CAPACITY - 1; i++) {
            last = algorithm.evaluate(remaining, BASE_TIME_MS, BASE_TIME_MS);
            remaining = last.remainingTokens();
        }

        // One request beyond capacity — the (limit+1)th
        final RateLimitDecision overflow = algorithm.evaluate(remaining, BASE_TIME_MS, BASE_TIME_MS);
        assertFalse(overflow.allowed(), "Request beyond capacity limit must be denied");
    }

    // ── Burst: consuming capacity tokens in quick succession ──────────────────

    @Test
    void burstOfCapacityRequestsAllAllowed() {
        double tokens = CAPACITY;
        int allowedCount = 0;

        for (int i = 0; i < CAPACITY; i++) {
            final RateLimitDecision d = algorithm.evaluate(tokens, BASE_TIME_MS, BASE_TIME_MS);
            if (d.allowed()) {
                allowedCount++;
                tokens = d.remainingTokens();
            }
        }

        assertEquals((int) CAPACITY, allowedCount, "All burst requests within capacity must be allowed");
    }

    // ── Refill ────────────────────────────────────────────────────────────────

    @Test
    void fullRefillAfterOneSecond() {
        // Start with 0 tokens; after 1 second (1000 ms) at rate=2, add 2 tokens
        final long oneSecondLater = BASE_TIME_MS + 1000L;
        final RateLimitDecision decision = algorithm.evaluate(0.0, BASE_TIME_MS, oneSecondLater);

        assertTrue(decision.allowed(), "Bucket should have refilled enough to allow a request");
        assertEquals(1L, decision.remainingTokens(),
            "After refill of 2 and consuming 1, remaining should be 1");
    }

    @Test
    void partialRefillDoesNotExceedCapacity() {
        // Start full; wait long enough that a naive refill would overflow capacity
        final long tenSecondsLater = BASE_TIME_MS + 10_000L;
        final RateLimitDecision decision =
            algorithm.evaluate(CAPACITY, BASE_TIME_MS, tenSecondsLater);

        assertTrue(decision.allowed());
        // Remaining must not exceed capacity - 1 (no overflow beyond the cap)
        assertTrue(decision.remainingTokens() <= CAPACITY - 1,
            "Remaining tokens must not exceed capacity after refill cap");
    }

    @Test
    void zeroElapsedTimeProducesNoRefill() {
        // No time has passed — tokens stay exactly as supplied
        final double startTokens = 5.0;
        final RateLimitDecision decision =
            algorithm.evaluate(startTokens, BASE_TIME_MS, BASE_TIME_MS);

        assertTrue(decision.allowed());
        assertEquals(REMAINING_AFTER_FIVE_MINUS_ONE, decision.remainingTokens());
    }

    // ── Reset hint ────────────────────────────────────────────────────────────

    @Test
    void resetHintIsPositiveWhenDenied() {
        final RateLimitDecision decision = algorithm.evaluate(0.0, BASE_TIME_MS, BASE_TIME_MS);

        assertFalse(decision.allowed());
        assertTrue(decision.resetAfterMs() > 0,
            "Denied decision must tell the caller how long to wait");
    }

    @Test
    void resetHintIsZeroWhenAllowed() {
        final RateLimitDecision decision =
            algorithm.evaluate(CAPACITY, BASE_TIME_MS, BASE_TIME_MS);

        assertTrue(decision.allowed());
        assertEquals(0L, decision.resetAfterMs());
    }

    // ── parseResult ───────────────────────────────────────────────────────────

    @Test
    void parseResultAllowed() {
        final List<Object> luaResult = List.of(1L, 9L, 0L);
        final RateLimitDecision decision = algorithm.parseResult(luaResult);

        assertTrue(decision.allowed());
        assertEquals(PARSE_RESULT_REMAINING, decision.remainingTokens());
        assertEquals(0L, decision.resetAfterMs());
    }

    @Test
    void parseResultDenied() {
        final List<Object> luaResult = List.of(0L, 0L, 500L);
        final RateLimitDecision decision = algorithm.parseResult(luaResult);

        assertFalse(decision.allowed());
        assertEquals(0L, decision.remainingTokens());
        assertEquals(PARSE_RESULT_RESET_MS, decision.resetAfterMs());
    }

    // ── buildLuaArgs ──────────────────────────────────────────────────────────

    @Test
    void buildLuaArgsContainsFourElements() {
        final List<String> args = algorithm.buildLuaArgs(BASE_TIME_MS);
        assertEquals(LUA_ARGS_COUNT, args.size());
    }

    @Test
    void buildLuaArgsCarriesCapacityAndRate() {
        final List<String> args = algorithm.buildLuaArgs(BASE_TIME_MS);
        assertEquals(String.valueOf(CAPACITY), args.get(0));
        assertEquals(String.valueOf(REFILL_RATE), args.get(1));
        assertEquals(String.valueOf(BASE_TIME_MS), args.get(2));
    }
}
