package com.fluxguard.engine;

import com.fluxguard.config.LimitConfig;
import com.fluxguard.exception.RedisUnavailableException;
import com.fluxguard.metrics.PrometheusMetricsCollector;
import com.fluxguard.model.ClientIdentity;
import com.fluxguard.redis.LuaScriptExecutor;
import com.fluxguard.util.ClockProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RateLimitEngine}.
 *
 * <p>No Spring context and no Redis: {@link LuaScriptExecutor} is mocked, a real
 * Resilience4j {@link CircuitBreaker} drives the open/closed transitions, a fixed
 * {@link ClockProvider} keeps the Lua args deterministic, a {@link SimpleMeterRegistry}
 * backs the metrics collector for assertions, and a no-op {@link Tracer} produces spans.
 */
class RateLimitEngineTest {

    private static final String KNOWN_PATH = "/api/test";
    private static final String CLIENT_ID = "client-abc";
    private static final long TEST_LIMIT = 10L;
    private static final long TEST_WINDOW_MS = 60_000L;
    private static final long FIXED_NOW_MS = 1_000_000L;
    private static final long MOCK_REMAINING = 7L;
    private static final long MOCK_RESET_MS = 30_000L;
    private static final String ALGORITHM_NAME = "sliding_window";
    private static final int CB_FAILURE_RATE_THRESHOLD = 100;
    private static final int CB_MIN_CALLS = 100;
    private static final double COUNTER_DELTA = 0.001;

    private LuaScriptExecutor mockExecutor;
    private SimpleMeterRegistry meterRegistry;
    private PrometheusMetricsCollector collector;
    private CircuitBreaker circuitBreaker;
    private RateLimitEngine engine;
    private LimitConfig config;
    private ClientIdentity identity;

    @BeforeEach
    void setUp() {
        mockExecutor = mock(LuaScriptExecutor.class);
        final ClockProvider mockClock = mock(ClockProvider.class);
        when(mockClock.nowMillis()).thenReturn(FIXED_NOW_MS);
        meterRegistry = new SimpleMeterRegistry();
        collector = new PrometheusMetricsCollector(meterRegistry);
        final CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(CB_FAILURE_RATE_THRESHOLD)
            .minimumNumberOfCalls(CB_MIN_CALLS)
            .build();
        circuitBreaker = CircuitBreaker.of("test-cb", cbConfig);
        final Tracer tracer = OpenTelemetry.noop().getTracer("test");
        engine = new RateLimitEngine(mockExecutor, mockClock, circuitBreaker, collector, tracer);
        config = LimitConfig.slidingWindow(KNOWN_PATH, TEST_LIMIT, TEST_WINDOW_MS);
        identity = ClientIdentity.of(CLIENT_ID, KNOWN_PATH);
    }

    @Test
    void allowDecisionReturnsAllowedAndRecordsAllowedMetric() {
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(1L, MOCK_REMAINING, 0L));

        final DecisionOutcome outcome = engine.decide(config, identity);

        assertTrue(outcome.decision().allowed());
        assertEquals(MOCK_REMAINING, outcome.decision().remainingTokens());
        assertEquals(PrometheusMetricsCollector.RESULT_ALLOWED, outcome.resultLabel());
        assertEquals(1.0, allowedCount(), COUNTER_DELTA);
        assertEquals(0.0, deniedCount(), COUNTER_DELTA);
    }

    @Test
    void denyDecisionReturnsDeniedAndRecordsDeniedMetric() {
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(0L, 0L, MOCK_RESET_MS));

        final DecisionOutcome outcome = engine.decide(config, identity);

        assertFalse(outcome.decision().allowed());
        assertEquals(MOCK_RESET_MS, outcome.decision().resetAfterMs());
        assertEquals(PrometheusMetricsCollector.RESULT_DENIED, outcome.resultLabel());
        assertEquals(1.0, deniedCount(), COUNTER_DELTA);
        assertEquals(0.0, allowedCount(), COUNTER_DELTA);
    }

    @Test
    void redisErrorFailsOpenWithRedisErrorReason() {
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenThrow(new RedisUnavailableException("connection refused"));

        final DecisionOutcome outcome = engine.decide(config, identity);

        assertTrue(outcome.decision().allowed(), "Redis error must fail open");
        assertEquals(0L, outcome.decision().remainingTokens());
        assertEquals(PrometheusMetricsCollector.RESULT_FAILOPEN, outcome.resultLabel());
        assertEquals(PrometheusMetricsCollector.REASON_REDIS_ERROR, outcome.failOpenReason());
        assertEquals(1.0, failOpenCount(PrometheusMetricsCollector.REASON_REDIS_ERROR), COUNTER_DELTA);
    }

    @Test
    void circuitOpenFailsOpenWithCircuitOpenReason() {
        circuitBreaker.transitionToOpenState();

        final DecisionOutcome outcome = engine.decide(config, identity);

        assertTrue(outcome.decision().allowed(), "Open circuit must fail open");
        assertEquals(PrometheusMetricsCollector.RESULT_FAILOPEN, outcome.resultLabel());
        assertEquals(PrometheusMetricsCollector.REASON_CIRCUIT_OPEN, outcome.failOpenReason());
        assertEquals(1.0, failOpenCount(PrometheusMetricsCollector.REASON_CIRCUIT_OPEN), COUNTER_DELTA);
        verify(mockExecutor, never()).execute(anyString(), anyList(), anyList());
    }

    private double allowedCount() {
        return counterValue(PrometheusMetricsCollector.METRIC_ALLOWED,
            PrometheusMetricsCollector.TAG_ALGORITHM, ALGORITHM_NAME);
    }

    private double deniedCount() {
        return counterValue(PrometheusMetricsCollector.METRIC_DENIED,
            PrometheusMetricsCollector.TAG_ALGORITHM, ALGORITHM_NAME);
    }

    private double failOpenCount(final String reason) {
        return counterValue(PrometheusMetricsCollector.METRIC_FAILOPEN,
            PrometheusMetricsCollector.TAG_REASON, reason);
    }

    private double counterValue(final String metric, final String tagKey, final String tagValue) {
        final Counter c = meterRegistry.find(metric)
            .tag(PrometheusMetricsCollector.TAG_ENDPOINT, KNOWN_PATH)
            .tag(tagKey, tagValue)
            .counter();
        return c == null ? 0.0 : c.count();
    }
}
