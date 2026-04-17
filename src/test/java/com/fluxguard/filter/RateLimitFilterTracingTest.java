package com.fluxguard.filter;

import com.fluxguard.config.ConfigService;
import com.fluxguard.config.LimitConfig;
import com.fluxguard.exception.RedisUnavailableException;
import com.fluxguard.metrics.PrometheusMetricsCollector;
import com.fluxguard.redis.LuaScriptExecutor;
import com.fluxguard.util.ClockProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tracing-focused unit tests for {@link RateLimitFilter}.
 *
 * <p>Uses {@link InMemorySpanExporter} to assert span names, attributes, status,
 * and current-context propagation without a Spring context or Redis.
 */
class RateLimitFilterTracingTest {

    private static final String CLIENT_ID_HEADER = "X-Client-ID";
    private static final String KNOWN_PATH = "/api/test";
    private static final String UNKNOWN_PATH = "/api/unknown";
    private static final String CLIENT_ID = "client-abc";
    private static final long TEST_LIMIT = 10L;
    private static final long TEST_WINDOW_MS = 60_000L;
    private static final long FIXED_NOW_MS = 1_000_000L;
    private static final long MOCK_REMAINING = 7L;
    private static final long MOCK_RESET_MS = 30_000L;
    private static final int CB_FAILURE_RATE_THRESHOLD = 100;
    private static final int CB_MIN_CALLS = 100;
    private static final String ALGORITHM_NAME = "sliding_window";
    private static final String SPAN_DECISION = "rate_limit.decision";
    private static final String ATTR_CLIENT_ID = "client.id";
    private static final String ATTR_ENDPOINT = "endpoint";
    private static final String ATTR_ALGORITHM = "algorithm";
    private static final String ATTR_DECISION = "decision";
    private static final String ATTR_FAIL_OPEN_REASON = "fail_open_reason";
    private static final String ATTR_REMAINING = "rate_limit.remaining";
    private static final String ATTR_RESET_AFTER_MS = "rate_limit.reset_after_ms";

    private LuaScriptExecutor mockExecutor;
    private ClockProvider mockClock;
    private CircuitBreaker circuitBreaker;
    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        tracer = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()
            .getTracer("test");
        mockExecutor = mock(LuaScriptExecutor.class);
        mockClock = mock(ClockProvider.class);
        when(mockClock.nowMillis()).thenReturn(FIXED_NOW_MS);
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(1L, MOCK_REMAINING, 0L));
        circuitBreaker = CircuitBreaker.of("test-cb", circuitBreakerConfig());
        filter = new RateLimitFilter(
            mockExecutor, stubConfigService(), mockClock, circuitBreaker, collector(), tracer);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void missingClientIdDoesNotCreateSpan() throws Exception {
        filter.preHandle(buildRequest(KNOWN_PATH), new MockHttpServletResponse(), new Object());

        assertTrue(exporter.getFinishedSpanItems().isEmpty());
        verify(mockExecutor, never()).execute(anyString(), anyList(), anyList());
    }

    @Test
    void unknownPathDoesNotCreateSpan() throws Exception {
        filter.preHandle(buildRequestWithClient(UNKNOWN_PATH), new MockHttpServletResponse(),
            new Object());

        assertTrue(exporter.getFinishedSpanItems().isEmpty());
        verify(mockExecutor, never()).execute(anyString(), anyList(), anyList());
    }

    @Test
    void allowedDecisionCreatesOkSpanWithRemainingAttribute() throws Exception {
        filter.preHandle(buildRequestWithClient(KNOWN_PATH), new MockHttpServletResponse(),
            new Object());

        final SpanData span = getOnlySpan();
        assertEquals(SPAN_DECISION, span.getName());
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertEquals(MOCK_REMAINING, longAttribute(span, ATTR_REMAINING));
        assertEquals(CLIENT_ID, stringAttribute(span, ATTR_CLIENT_ID));
        assertEquals(ALGORITHM_NAME, stringAttribute(span, ATTR_ALGORITHM));
        assertEquals(PrometheusMetricsCollector.RESULT_ALLOWED, stringAttribute(span, ATTR_DECISION));
    }

    @Test
    void deniedDecisionCreatesOkSpanWithResetAfterAttribute() throws Exception {
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(0L, 0L, MOCK_RESET_MS));

        filter.preHandle(buildRequestWithClient(KNOWN_PATH), new MockHttpServletResponse(),
            new Object());

        final SpanData span = getOnlySpan();
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertEquals(MOCK_RESET_MS, longAttribute(span, ATTR_RESET_AFTER_MS));
        assertEquals(PrometheusMetricsCollector.RESULT_DENIED, stringAttribute(span, ATTR_DECISION));
    }

    @Test
    void redisErrorFailOpenCreatesErrorSpan() throws Exception {
        when(mockExecutor.execute(anyString(), anyList(), anyList()))
            .thenThrow(new RedisUnavailableException("timeout"));

        filter.preHandle(buildRequestWithClient(KNOWN_PATH), new MockHttpServletResponse(),
            new Object());

        final SpanData span = getOnlySpan();
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals(PrometheusMetricsCollector.REASON_REDIS_ERROR,
            stringAttribute(span, ATTR_FAIL_OPEN_REASON));
        assertEquals(PrometheusMetricsCollector.RESULT_FAILOPEN,
            stringAttribute(span, ATTR_DECISION));
    }

    @Test
    void circuitOpenFailOpenCreatesOkSpan() throws Exception {
        circuitBreaker.transitionToOpenState();

        filter.preHandle(buildRequestWithClient(KNOWN_PATH), new MockHttpServletResponse(),
            new Object());

        final SpanData span = getOnlySpan();
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertEquals(PrometheusMetricsCollector.REASON_CIRCUIT_OPEN,
            stringAttribute(span, ATTR_FAIL_OPEN_REASON));
        assertEquals(PrometheusMetricsCollector.RESULT_FAILOPEN,
            stringAttribute(span, ATTR_DECISION));
    }

    @Test
    void executeAndApplyMakesSpanCurrentForExecutorCall() throws Exception {
        final String[] observedTraceId = {""};
        final String[] observedSpanId = {""};
        when(mockExecutor.execute(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            final Span current = Span.current();
            observedTraceId[0] = current.getSpanContext().getTraceId();
            observedSpanId[0] = current.getSpanContext().getSpanId();
            return List.of(1L, MOCK_REMAINING, 0L);
        });

        filter.preHandle(buildRequestWithClient(KNOWN_PATH), new MockHttpServletResponse(),
            new Object());

        final SpanData span = getOnlySpan();
        assertEquals(span.getTraceId(), observedTraceId[0]);
        assertEquals(span.getSpanId(), observedSpanId[0]);
    }

    @Test
    void decisionSpanUsesInternalKind() throws Exception {
        filter.preHandle(buildRequestWithClient(KNOWN_PATH), new MockHttpServletResponse(),
            new Object());

        assertEquals(SpanKind.INTERNAL, getOnlySpan().getKind());
    }

    @Test
    void decisionSpanIncludesEndpointAttribute() throws Exception {
        filter.preHandle(buildRequestWithClient(KNOWN_PATH), new MockHttpServletResponse(),
            new Object());

        assertEquals(KNOWN_PATH, stringAttribute(getOnlySpan(), ATTR_ENDPOINT));
    }

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

    private ConfigService stubConfigService() {
        final ConfigService stub = org.mockito.Mockito.mock(ConfigService.class);
        org.mockito.Mockito.when(stub.isKillSwitchActive()).thenReturn(false);
        org.mockito.Mockito.when(stub.getConfig(KNOWN_PATH))
            .thenReturn(Optional.of(LimitConfig.slidingWindow(KNOWN_PATH, TEST_LIMIT, TEST_WINDOW_MS)));
        org.mockito.Mockito.when(stub.getConfig(UNKNOWN_PATH)).thenReturn(Optional.empty());
        return stub;
    }

    private PrometheusMetricsCollector collector() {
        return new PrometheusMetricsCollector(new SimpleMeterRegistry());
    }

    private CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
            .failureRateThreshold(CB_FAILURE_RATE_THRESHOLD)
            .minimumNumberOfCalls(CB_MIN_CALLS)
            .build();
    }

    private SpanData getOnlySpan() {
        final List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        return spans.get(0);
    }

    private String stringAttribute(final SpanData span, final String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private long longAttribute(final SpanData span, final String key) {
        return span.getAttributes().get(AttributeKey.longKey(key));
    }
}
