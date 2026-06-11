package com.fluxguard.integration;

import com.fluxguard.algorithm.SlidingWindowAlgorithm;
import com.fluxguard.model.RateLimitDecision;
import com.fluxguard.redis.LuaScriptExecutor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the sliding-window rate-limit algorithm against a real Redis instance.
 *
 * <p>These tests use Testcontainers to spin up {@code redis:7-alpine} and a full Spring
 * Boot context. They verify that {@code sliding_window.lua} produces correct decisions
 * at boundary conditions (exact limit, limit+1, previous-window weighted decay).
 *
 * <p>Naming convention: {@code IT} suffix causes Maven Failsafe to include this class
 * in the integration-test phase ({@code mvn verify}), not the unit-test phase
 * ({@code mvn test}).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SlidingWindowIT {

    private static final int REDIS_PORT = 6379;
    private static final long LIMIT = 10L;
    private static final long WINDOW_MS = 60_000L;

    /** Base epoch time anchored to the start of a window to keep tests deterministic. */
    private static final long BASE_TIME_MS = 1_200_000L;

    /** Time at the midpoint of the window that starts at BASE_TIME_MS. */
    private static final long MID_WINDOW_MS = BASE_TIME_MS + WINDOW_MS / 2;

    /** Prefix appended to bucket keys to isolate test scenarios from each other. */
    private static final String KEY_PREFIX = "it-test:";

    @Container
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(REDIS_PORT);

    /**
     * Injects the Testcontainers Redis host/port into the Spring context before
     * it starts, overriding the values from {@code application.yml}.
     *
     * @param reg dynamic property registry provided by Spring Test
     */
    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry reg) {
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired
    private LuaScriptExecutor executor;

    private SlidingWindowAlgorithm algorithm;

    /** Fresh algorithm instance before each test. */
    @BeforeEach
    void setUp() {
        algorithm = new SlidingWindowAlgorithm(LIMIT, WINDOW_MS);
    }

    /**
     * Verifies that exactly {@code limit} requests within a fresh window are all allowed
     * and that remaining drops to zero on the final permitted request.
     */
    @Test
    void exactLimitAllowsAllRequests() {
        final String bucketKey = KEY_PREFIX + "exact-limit";
        RateLimitDecision last = null;

        for (int i = 0; i < LIMIT; i++) {
            last = callAlgorithm(bucketKey, BASE_TIME_MS);
            assertTrue(last.allowed(), "Request " + (i + 1) + " of " + LIMIT + " must be allowed");
        }

        assertEquals(0L, last.remainingTokens(),
            "Remaining must be zero after consuming the full limit");
    }

    /**
     * Verifies that the (limit+1)th request in a window is denied.
     */
    @Test
    void limitPlusOneRequestIsDenied() {
        final String bucketKey = KEY_PREFIX + "limit-plus-one";

        for (int i = 0; i < LIMIT; i++) {
            callAlgorithm(bucketKey, BASE_TIME_MS);
        }

        final RateLimitDecision overflow = callAlgorithm(bucketKey, BASE_TIME_MS);
        assertFalse(overflow.allowed(), "Request beyond limit must be denied");
        assertTrue(overflow.resetAfterMs() > 0,
            "Denied decision must carry a positive reset hint");
    }

    /**
     * Verifies that a fully filled previous window reduces available capacity in the
     * next window according to the weighted-interpolation formula.
     *
     * <p>At the midpoint of window N+1 (positionMs = WINDOW_MS/2, weight = 0.5):
     * <pre>
     *   estimated = LIMIT × 0.5 + 0 = LIMIT/2
     *   remaining = LIMIT − LIMIT/2 − 1 = LIMIT/2 − 1
     * </pre>
     */
    @Test
    void previousWindowWeightReducesCapacity() {
        final String bucketKey = KEY_PREFIX + "prev-window-weight";
        final long prevWindowTime = BASE_TIME_MS;
        final long nextWindowTime = BASE_TIME_MS + WINDOW_MS;

        // Fill the previous window to capacity
        for (int i = 0; i < LIMIT; i++) {
            callAlgorithm(bucketKey, prevWindowTime);
        }

        // At the midpoint of the next window, previous weight = 0.5
        final RateLimitDecision decision = callAlgorithm(bucketKey, nextWindowTime + WINDOW_MS / 2);
        assertTrue(decision.allowed(),
            "First request in next window under weighted estimate must be allowed");
        assertEquals(LIMIT / 2 - 1, decision.remainingTokens(),
            "Remaining must reflect LIMIT − (LIMIT×0.5) − 1 at window midpoint");
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private RateLimitDecision callAlgorithm(final String bucketKey, final long nowMs) {
        final List<String> keys = algorithm.buildLuaKeys(bucketKey, nowMs);
        final List<String> args = algorithm.buildLuaArgs(nowMs);
        final List<Object> result = executor.execute(algorithm.luaScriptName(), keys, args);
        return algorithm.parseResult(result);
    }
}
