package com.fluxguard.redis;

import com.fluxguard.exception.RedisUnavailableException;
import com.fluxguard.metrics.PrometheusMetricsCollector;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tracing-focused unit tests for {@link LuaScriptExecutor}.
 *
 * <p>Uses {@link InMemorySpanExporter} to assert child span attributes, error
 * handling, and parent-child relationships without a live Redis instance.
 */
class LuaScriptExecutorTracingTest {

    private static final String SCRIPT_NAME = "token_bucket";
    private static final String SPAN_REDIS_SCRIPT = "redis.lua_script";
    private static final String TAG_SCRIPT_NAME = "script_name";
    private static final String TAG_KEY_COUNT = "key_count";
    private static final String PARENT_SPAN = "parent";
    private static final long LUA_REMAINING = 9L;

    private StringRedisTemplate redisTemplate;
    private PrometheusMetricsCollector metrics;
    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;
    private LuaScriptExecutor executor;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        metrics = mock(PrometheusMetricsCollector.class);
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        tracer = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()
            .getTracer("test");
        executor = new LuaScriptExecutor(redisTemplate, metrics, tracer);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void executeCreatesClientSpanWithScriptAttributes() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(List.of(1L, LUA_REMAINING, 0L));

        executor.execute(SCRIPT_NAME, List.of("bucket-key"), List.of("1", "2"));

        final SpanData span = getRedisSpan();
        assertEquals(SPAN_REDIS_SCRIPT, span.getName());
        assertEquals(SpanKind.CLIENT, span.getKind());
        assertEquals(SCRIPT_NAME, stringAttribute(span, TAG_SCRIPT_NAME));
        assertEquals(1L, longAttribute(span, TAG_KEY_COUNT));
        verify(metrics).recordScriptDuration(eq(SCRIPT_NAME), anyLong());
    }

    @Test
    void nullResultMarksSpanErrorAndThrows() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(null);

        assertThrows(RedisUnavailableException.class,
            () -> executor.execute(SCRIPT_NAME, List.of("bucket-key"), List.of("1")));

        final SpanData span = getRedisSpan();
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("Redis returned null for script: token_bucket",
            span.getStatus().getDescription());
        verify(metrics).recordScriptDuration(eq(SCRIPT_NAME), anyLong());
    }

    @Test
    void redisSpanUsesCurrentParentContext() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(List.of(1L, LUA_REMAINING, 0L));
        final Span parent = tracer.spanBuilder(PARENT_SPAN).startSpan();

        try (Scope ignored = parent.makeCurrent()) {
            executor.execute(SCRIPT_NAME, List.of("bucket-key"), List.of("1"));
        } finally {
            parent.end();
        }

        final SpanData parentSpan = getSpanByName(PARENT_SPAN);
        final SpanData childSpan = getRedisSpan();
        assertEquals(parentSpan.getSpanId(), childSpan.getParentSpanId());
        assertEquals(parentSpan.getTraceId(), childSpan.getTraceId());
    }

    private SpanData getRedisSpan() {
        return getSpanByName(SPAN_REDIS_SCRIPT);
    }

    private SpanData getSpanByName(final String spanName) {
        return exporter.getFinishedSpanItems().stream()
            .filter(span -> spanName.equals(span.getName()))
            .findFirst()
            .orElseThrow();
    }

    private String stringAttribute(final SpanData span, final String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private long longAttribute(final SpanData span, final String key) {
        return span.getAttributes().get(AttributeKey.longKey(key));
    }
}
