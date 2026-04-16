package com.fluxguard.filter;

import com.fluxguard.config.LimitConfig;
import com.fluxguard.exception.RedisUnavailableException;
import com.fluxguard.redis.LuaScriptExecutor;
import com.fluxguard.util.ClockProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <p>All tests are pure Java — no Spring context, no Redis.
 * {@link LuaScriptExecutor} is mocked with Mockito.
 * A real Resilience4j {@link CircuitBreaker} is used so circuit-state transitions
 * can be verified by calling {@link CircuitBreaker#transitionToOpenState()}.
 */
class RateLimitFilterTest {

    private static final String CLIENT_ID_HEADER = "X-Client-ID";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RETRY_AFTER_HEADER = "Retry-After";
    private static final String KNOWN_PATH = "/api/test";
    private static final String UNKNOWN_PATH = "/api/unknown";
    private static final String CLIENT_ID = "client-abc";

    /** Limit and window for the test endpoint config. */
    private static final long TEST_LIMIT = 10L;
    private static final long TEST_WINDOW_MS = 60_000L;

    /** Remaining tokens returned by a mock allow decision. */
    private static final long MOCK_REMAINING = 7L;

    /** Reset hint in ms returned by a mock deny decision; 30 000 ms = 30 s. */
    private static final long MOCK_RESET_MS = 30_000L;

    /** Expected Retry-After header value in seconds (ceil of MOCK_RESET_MS / 1000). */
    private static final String EXPECTED_RETRY_AFTER_SECONDS = "30";

    /** HTTP 200 OK status code. */
    private static final int STATUS_OK = 200;

    /** HTTP 400 Bad Request status code. */
    private static final int STATUS_BAD_REQUEST = 400;

    /** HTTP 429 Too Many Requests status code. */
    private static final int STATUS_TOO_MANY = 429;

    /** Delta for double equality assertions on counter values. */
    private static final double COUNTER_DELTA = 0.001;

    /**
     * High failure-rate threshold for the test circuit breaker, preventing it from
     * opening during normal tests (requires 100% failure rate across 100 calls).
     */
    private static final int CB_FAILURE_RATE_THRESHOLD = 100;

    /** Minimum number of calls the test CB requires before evaluating failure rate. */
    private static final int CB_MIN_CALLS = 100;

    /** Fixed epoch timestamp used for ClockProvider stub. */
    private static final long FIXED_NOW_MS = 1_000_000L;

    private LuaScriptExecutor mockExecutor;
    private ClockProvider mockClock;
    private SimpleMeterRegistry meterRegistry;
    private CircuitBreaker circuitBreaker;
    private RateLimitFilter filter;

    /** Fresh filter instance before each test; CB starts in CLOSED state. */
    @BeforeEach
    void setUp() {
        mockExecutor = mock(LuaScriptExecutor.class);
        mockClock = mock(ClockProvider.class);
        when(mockClock.nowMillis()).thenReturn(FIXED_NOW_MS);

        meterRegistry = new SimpleMeterRegistry();

        // CB with high thresholds so it never opens unexpectedly during normal tests
        final CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(CB_FAILURE_RATE_THRESHOLD)
            .minimumNumberOfCalls(CB_MIN_CALLS)
            .build();
        circuitBreaker = CircuitBreaker.of("test-cb", cbConfig);

        final Map<String, LimitConfig> configs = Map.of(
            KNOWN_PATH, LimitConfig.slidingWindow(KNOWN_PATH, TEST_LIMIT, TEST_WINDOW_MS)
        );

        // Stub executor to return a valid Lua result list for the known path
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(1L, MOCK_REMAINING, 0L));

        filter = new RateLimitFilter(
            mockExecutor, configs, mockClock, circuitBreaker, meterRegistry);
    }

    // ── 400 Bad Request ───────────────────────────────────────────────────────

    @Test
    void missingClientIdHeaderReturnsBadRequest() throws Exception {
        final MockHttpServletRequest req = buildRequest(KNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        final boolean proceed = filter.preHandle(req, res, new Object());

        assertFalse(proceed, "preHandle must return false on missing header");
        assertEquals(STATUS_BAD_REQUEST, res.getStatus());
        verify(mockExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void blankClientIdHeaderReturnsBadRequest() throws Exception {
        final MockHttpServletRequest req = buildRequest(KNOWN_PATH);
        req.addHeader(CLIENT_ID_HEADER, "   ");
        final MockHttpServletResponse res = new MockHttpServletResponse();

        final boolean proceed = filter.preHandle(req, res, new Object());

        assertFalse(proceed);
        assertEquals(STATUS_BAD_REQUEST, res.getStatus());
        verify(mockExecutor, never()).execute(any(), any(), any());
    }

    // ── Unknown path (fail-open) ──────────────────────────────────────────────

    @Test
    void unknownEndpointSkipsLimitingAndProceed() throws Exception {
        final MockHttpServletRequest req = buildRequestWithClient(UNKNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        final boolean proceed = filter.preHandle(req, res, new Object());

        assertTrue(proceed, "Unknown path must pass through without limiting");
        assertEquals(STATUS_OK, res.getStatus());
        verify(mockExecutor, never()).execute(any(), any(), any());
    }

    // ── Allow path ────────────────────────────────────────────────────────────

    @Test
    void allowedRequestSetsRemainingHeader() throws Exception {
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        final boolean proceed = filter.preHandle(req, res, new Object());

        assertTrue(proceed);
        assertEquals(String.valueOf(MOCK_REMAINING), res.getHeader(REMAINING_HEADER));
    }

    @Test
    void allowedRequestDoesNotSet429() throws Exception {
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        filter.preHandle(req, res, new Object());

        assertEquals(STATUS_OK, res.getStatus());
    }

    @Test
    void remainingHeaderNotSetWhenRemainingIsNegative() throws Exception {
        // Simulate fail-open returning remaining=0 (the sentinel we use for fail-open)
        // Additional edge: force remaining = -1 via a custom executor response
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(1L, -1L, 0L));
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        filter.preHandle(req, res, new Object());

        assertNull(res.getHeader(REMAINING_HEADER),
            "X-RateLimit-Remaining must not be set when remaining is negative");
    }

    // ── Deny path ─────────────────────────────────────────────────────────────

    @Test
    void deniedRequestReturns429() throws Exception {
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(0L, 0L, MOCK_RESET_MS));
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        final boolean proceed = filter.preHandle(req, res, new Object());

        assertFalse(proceed);
        assertEquals(STATUS_TOO_MANY, res.getStatus());
    }

    @Test
    void deniedRequestSetsRetryAfterInSeconds() throws Exception {
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(0L, 0L, MOCK_RESET_MS));
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        filter.preHandle(req, res, new Object());

        assertEquals(EXPECTED_RETRY_AFTER_SECONDS, res.getHeader(RETRY_AFTER_HEADER));
    }

    @Test
    void retryAfterRoundsUpSubSecondValues() throws Exception {
        // 1 ms deny → ceil(1/1000) = 1 second, not 0
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(0L, 0L, 1L));
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        filter.preHandle(req, res, new Object());

        assertEquals("1", res.getHeader(RETRY_AFTER_HEADER),
            "Sub-second reset hint must round up to at least 1 second");
    }

    // ── Fail-open: Redis error ────────────────────────────────────────────────

    @Test
    void redisUnavailableExceptionFailsOpen() throws Exception {
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenThrow(new RedisUnavailableException("connection refused"));
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        final boolean proceed = filter.preHandle(req, res, new Object());

        assertTrue(proceed, "Redis error must fail open and allow request");
        assertEquals(STATUS_OK, res.getStatus());
    }

    @Test
    void redisUnavailableExceptionIncrementsRedisErrorCounter() throws Exception {
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenThrow(new RedisUnavailableException("timeout"));
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        filter.preHandle(req, res, new Object());

        final double count = getFailOpenCount("redis_error");
        assertEquals(1.0, count, COUNTER_DELTA, "redis_error counter must increment once");
    }

    @Test
    void circuitOpenCounterNotIncrementedOnRedisError() throws Exception {
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenThrow(new RedisUnavailableException("timeout"));
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);

        filter.preHandle(req, new MockHttpServletResponse(), new Object());

        assertEquals(0.0, getFailOpenCount("circuit_open"), COUNTER_DELTA);
    }

    // ── Fail-open: circuit breaker open ──────────────────────────────────────

    @Test
    void circuitBreakerOpenFailsOpen() throws Exception {
        circuitBreaker.transitionToOpenState();
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);
        final MockHttpServletResponse res = new MockHttpServletResponse();

        final boolean proceed = filter.preHandle(req, res, new Object());

        assertTrue(proceed, "Open circuit must fail open and allow request");
        assertEquals(STATUS_OK, res.getStatus());
        verify(mockExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void circuitBreakerOpenIncrementsCircuitOpenCounter() throws Exception {
        circuitBreaker.transitionToOpenState();
        final MockHttpServletRequest req = buildRequestWithClient(KNOWN_PATH);

        filter.preHandle(req, new MockHttpServletResponse(), new Object());

        assertEquals(1.0, getFailOpenCount("circuit_open"), COUNTER_DELTA);
        assertEquals(0.0, getFailOpenCount("redis_error"), COUNTER_DELTA);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private MockHttpServletRequest buildRequest(final String path) {
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(path);
        return req;
    }

    private MockHttpServletRequest buildRequestWithClient(final String path) {
        final MockHttpServletRequest req = buildRequest(path);
        req.addHeader(CLIENT_ID_HEADER, CLIENT_ID);
        return req;
    }

    private double getFailOpenCount(final String reason) {
        final Counter counter = meterRegistry.find("redis.failopen.total")
            .tag("reason", reason)
            .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
