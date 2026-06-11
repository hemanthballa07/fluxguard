package com.fluxguard.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link PrometheusMetricsCollector}.
 *
 * <p>All tests are pure Java — no Spring context.
 * A {@link SimpleMeterRegistry} is used so counter and timer values can be
 * asserted directly without a running Prometheus endpoint.
 */
class PrometheusMetricsCollectorTest {

    private static final double COUNTER_DELTA = 0.001;
    private static final String ENDPOINT   = "/api/test";
    private static final String ALGORITHM  = "sliding_window";
    private static final long   SAMPLE_NS  = 500_000L;

    private SimpleMeterRegistry registry;
    private PrometheusMetricsCollector collector;

    /** Fresh registry and collector before each test to prevent cross-test pollution. */
    @BeforeEach
    void setUp() {
        registry  = new SimpleMeterRegistry();
        collector = new PrometheusMetricsCollector(registry);
    }

    // ── allowed counter ───────────────────────────────────────────────────────

    @Test
    void recordAllowedIncrementsAllowedCounter() {
        collector.recordAllowed(ENDPOINT, ALGORITHM);

        assertEquals(1.0, getAllowedCount(ENDPOINT, ALGORITHM), COUNTER_DELTA);
    }

    @Test
    void recordAllowedDoesNotIncrementDeniedCounter() {
        collector.recordAllowed(ENDPOINT, ALGORITHM);

        assertEquals(0.0, getDeniedCount(ENDPOINT, ALGORITHM), COUNTER_DELTA);
    }

    // ── denied counter ────────────────────────────────────────────────────────

    @Test
    void recordDeniedIncrementsDeniedCounter() {
        collector.recordDenied(ENDPOINT, ALGORITHM);

        assertEquals(1.0, getDeniedCount(ENDPOINT, ALGORITHM), COUNTER_DELTA);
    }

    @Test
    void recordDeniedDoesNotIncrementAllowedCounter() {
        collector.recordDenied(ENDPOINT, ALGORITHM);

        assertEquals(0.0, getAllowedCount(ENDPOINT, ALGORITHM), COUNTER_DELTA);
    }

    // ── failopen counter ──────────────────────────────────────────────────────

    @Test
    void recordFailOpenRedisErrorIncrementsRedisErrorTag() {
        collector.recordFailOpen(ENDPOINT, PrometheusMetricsCollector.REASON_REDIS_ERROR);

        assertEquals(1.0,
            getFailOpenCount(ENDPOINT, PrometheusMetricsCollector.REASON_REDIS_ERROR),
            COUNTER_DELTA);
    }

    @Test
    void recordFailOpenRedisErrorDoesNotIncrementCircuitOpenTag() {
        collector.recordFailOpen(ENDPOINT, PrometheusMetricsCollector.REASON_REDIS_ERROR);

        assertEquals(0.0,
            getFailOpenCount(ENDPOINT, PrometheusMetricsCollector.REASON_CIRCUIT_OPEN),
            COUNTER_DELTA);
    }

    @Test
    void recordFailOpenCircuitOpenIncrementsCircuitOpenTag() {
        collector.recordFailOpen(ENDPOINT, PrometheusMetricsCollector.REASON_CIRCUIT_OPEN);

        assertEquals(1.0,
            getFailOpenCount(ENDPOINT, PrometheusMetricsCollector.REASON_CIRCUIT_OPEN),
            COUNTER_DELTA);
    }

    @Test
    void recordFailOpenCircuitOpenDoesNotIncrementRedisErrorTag() {
        collector.recordFailOpen(ENDPOINT, PrometheusMetricsCollector.REASON_CIRCUIT_OPEN);

        assertEquals(0.0,
            getFailOpenCount(ENDPOINT, PrometheusMetricsCollector.REASON_REDIS_ERROR),
            COUNTER_DELTA);
    }

    // ── decision duration timer ───────────────────────────────────────────────

    @Test
    void recordDecisionDurationRegistersTimerWithOneSample() {
        collector.recordDecisionDuration(
            ENDPOINT, ALGORITHM, PrometheusMetricsCollector.RESULT_ALLOWED, SAMPLE_NS);

        final Timer timer = registry.find(PrometheusMetricsCollector.METRIC_DECISION_DUR)
            .tag(PrometheusMetricsCollector.TAG_ENDPOINT, ENDPOINT)
            .tag(PrometheusMetricsCollector.TAG_ALGORITHM, ALGORITHM)
            .tag(PrometheusMetricsCollector.TAG_RESULT, PrometheusMetricsCollector.RESULT_ALLOWED)
            .timer();
        assertNotNull(timer, "Decision duration timer must be registered after first call");
        assertEquals(1L, timer.count(), "Timer must record exactly one sample");
    }

    // ── script duration timer ─────────────────────────────────────────────────

    @Test
    void recordScriptDurationRegistersTimerWithOneSample() {
        collector.recordScriptDuration("token_bucket", SAMPLE_NS);

        final Timer timer = registry.find(PrometheusMetricsCollector.METRIC_SCRIPT_DUR)
            .tag(PrometheusMetricsCollector.TAG_SCRIPT, "token_bucket")
            .timer();
        assertNotNull(timer, "Script duration timer must be registered after first call");
        assertEquals(1L, timer.count(), "Timer must record exactly one sample");
    }

    // ── tag isolation ─────────────────────────────────────────────────────────

    @Test
    void differentEndpointsGetSeparateCounterInstances() {
        collector.recordAllowed(ENDPOINT, ALGORITHM);
        collector.recordAllowed("/api/other", ALGORITHM);

        assertEquals(1.0, getAllowedCount(ENDPOINT, ALGORITHM), COUNTER_DELTA);
        assertEquals(1.0, getAllowedCount("/api/other", ALGORITHM), COUNTER_DELTA);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private double getAllowedCount(final String endpoint, final String algorithm) {
        final Counter c = registry.find(PrometheusMetricsCollector.METRIC_ALLOWED)
            .tag(PrometheusMetricsCollector.TAG_ENDPOINT, endpoint)
            .tag(PrometheusMetricsCollector.TAG_ALGORITHM, algorithm)
            .counter();
        return c == null ? 0.0 : c.count();
    }

    private double getDeniedCount(final String endpoint, final String algorithm) {
        final Counter c = registry.find(PrometheusMetricsCollector.METRIC_DENIED)
            .tag(PrometheusMetricsCollector.TAG_ENDPOINT, endpoint)
            .tag(PrometheusMetricsCollector.TAG_ALGORITHM, algorithm)
            .counter();
        return c == null ? 0.0 : c.count();
    }

    private double getFailOpenCount(final String endpoint, final String reason) {
        final Counter c = registry.find(PrometheusMetricsCollector.METRIC_FAILOPEN)
            .tag(PrometheusMetricsCollector.TAG_ENDPOINT, endpoint)
            .tag(PrometheusMetricsCollector.TAG_REASON, reason)
            .counter();
        return c == null ? 0.0 : c.count();
    }
}
