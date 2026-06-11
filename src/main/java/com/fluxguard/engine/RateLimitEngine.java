package com.fluxguard.engine;

import com.fluxguard.config.LimitConfig;
import com.fluxguard.metrics.PrometheusMetricsCollector;
import com.fluxguard.model.ClientIdentity;
import com.fluxguard.model.RateLimitDecision;
import com.fluxguard.redis.LuaScriptExecutor;
import com.fluxguard.util.ClockProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Transport-agnostic rate-limit decision core.
 *
 * <p>This is the single component that calls Redis to make a rate-limit decision,
 * wrapping the call in a Resilience4j circuit breaker and emitting metrics plus an
 * OpenTelemetry decision span. It returns a {@link DecisionOutcome} and never touches
 * any HTTP machinery, so it can be shared across the servlet filter and the gRPC path.
 *
 * <p>Fail-open semantics are preserved verbatim from the original filter: an open
 * circuit or any Redis {@link RuntimeException} yields an allow(0) decision tagged with
 * the corresponding {@link PrometheusMetricsCollector} reason.
 */
@Component
public class RateLimitEngine {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitEngine.class);

    /** Span name used for the full rate-limit decision flow. */
    private static final String SPAN_RATE_LIMIT_DECISION = "rate_limit.decision";

    /** Span attribute key for the HTTP endpoint being rate limited. */
    private static final String TAG_ENDPOINT = "endpoint";

    /** Span attribute key for the client identifier driving the decision. */
    private static final String TAG_CLIENT_ID = "client.id";

    /** Span attribute key for the algorithm used for the decision. */
    private static final String TAG_ALGORITHM = "algorithm";

    /** Span attribute key for the final business decision label. */
    private static final String TAG_DECISION = "decision";

    /** Span attribute key for the fail-open reason when applicable. */
    private static final String TAG_FAIL_OPEN_REASON = "fail_open_reason";

    /** Span attribute key for the remaining capacity on an allowed decision. */
    private static final String TAG_RATE_LIMIT_REMAINING = "rate_limit.remaining";

    /** Span attribute key for reset delay on a denied decision. */
    private static final String TAG_RATE_LIMIT_RESET_AFTER_MS = "rate_limit.reset_after_ms";

    private final LuaScriptExecutor executor;
    private final ClockProvider clock;
    private final CircuitBreaker circuitBreaker;
    private final PrometheusMetricsCollector metrics;
    private final Tracer tracer;

    /**
     * Constructs the engine with all required collaborators.
     *
     * @param executor       executes Lua scripts against Redis
     * @param clock          injectable clock; never call {@code System.currentTimeMillis()} directly
     * @param circuitBreaker Resilience4j circuit breaker wrapping Redis calls
     * @param metrics        Micrometer metrics facade for all rate-limit signals
     * @param tracer         tracer used to create rate-limit decision spans
     */
    public RateLimitEngine(
            final LuaScriptExecutor executor,
            final ClockProvider clock,
            final CircuitBreaker circuitBreaker,
            final PrometheusMetricsCollector metrics,
            final Tracer tracer) {
        this.executor = executor;
        this.clock = clock;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    /**
     * Makes a rate-limit decision for the given client and endpoint.
     *
     * <p>Builds the {@code rate_limit.decision} span, runs the circuit-breaker-wrapped
     * Redis call, records metrics and span attributes, and returns the outcome. This
     * method performs no I/O against the response; callers apply the decision.
     *
     * @param config   the resolved rate-limit configuration for the endpoint
     * @param identity the client/endpoint subject of the decision
     * @return the decision plus its metrics labels and any fail-open reason
     */
    public DecisionOutcome decide(final LimitConfig config, final ClientIdentity identity) {
        final String algorithmName = config.algorithm().luaScriptName();
        final Span span = tracer.spanBuilder(SPAN_RATE_LIMIT_DECISION)
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            final long startNs = System.nanoTime();
            final DecisionOutcome outcome = executeWithCircuitBreaker(identity, config);
            recordMetrics(identity.endpoint(), algorithmName, outcome, System.nanoTime() - startNs);
            annotateSpan(span, identity.clientId(), identity.endpoint(), algorithmName, outcome);
            return outcome;
        } finally {
            span.end();
        }
    }

    /**
     * Executes the rate-limit script for the given subject without metrics, spans, or
     * the circuit breaker.
     *
     * <p>Used by the filter's dark-launch shadow path, which must observe the raw
     * decision and handle any thrown {@link RuntimeException} itself.
     *
     * @param config   the resolved rate-limit configuration for the endpoint
     * @param identity the client/endpoint subject of the decision
     * @return the raw rate-limit decision
     */
    public RateLimitDecision executeRaw(final LimitConfig config, final ClientIdentity identity) {
        final long now = clock.nowMillis();
        final List<String> keys = config.algorithm().buildLuaKeys(identity.bucketKey(), now);
        final List<String> args = config.algorithm().buildLuaArgs(now);
        final List<Object> result =
            executor.execute(config.algorithm().luaScriptName(), keys, args);
        return config.algorithm().parseResult(result);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void recordMetrics(
            final String path,
            final String algorithmName,
            final DecisionOutcome outcome,
            final long elapsedNs) {
        metrics.recordDecisionDuration(path, algorithmName, outcome.resultLabel(), elapsedNs);
        if (PrometheusMetricsCollector.RESULT_FAILOPEN.equals(outcome.resultLabel())) {
            metrics.recordFailOpen(path, outcome.failOpenReason());
        } else if (outcome.decision().allowed()) {
            metrics.recordAllowed(path, algorithmName);
        } else {
            metrics.recordDenied(path, algorithmName);
        }
    }

    private void annotateSpan(
            final Span span,
            final String clientId,
            final String path,
            final String algorithmName,
            final DecisionOutcome outcome) {
        final String decision = outcome.resultLabel();
        span.setAttribute(TAG_CLIENT_ID, clientId);
        span.setAttribute(TAG_ENDPOINT, path);
        span.setAttribute(TAG_ALGORITHM, algorithmName);
        span.setAttribute(TAG_DECISION, decision);
        if (PrometheusMetricsCollector.RESULT_ALLOWED.equals(decision)) {
            span.setAttribute(TAG_RATE_LIMIT_REMAINING, outcome.decision().remainingTokens());
            span.setStatus(StatusCode.OK);
            return;
        }
        if (PrometheusMetricsCollector.RESULT_DENIED.equals(decision)) {
            span.setAttribute(TAG_RATE_LIMIT_RESET_AFTER_MS, outcome.decision().resetAfterMs());
            span.setStatus(StatusCode.OK);
            return;
        }
        if (PrometheusMetricsCollector.REASON_REDIS_ERROR.equals(outcome.failOpenReason())) {
            span.setAttribute(TAG_FAIL_OPEN_REASON, outcome.failOpenReason());
            span.setStatus(StatusCode.ERROR, "Redis failure - failing open");
            return;
        }
        if (outcome.failOpenReason() != null) {
            span.setAttribute(TAG_FAIL_OPEN_REASON, outcome.failOpenReason());
        }
        span.setStatus(StatusCode.OK);
    }

    private DecisionOutcome executeWithCircuitBreaker(
            final ClientIdentity identity,
            final LimitConfig config) {
        final Supplier<RateLimitDecision> decorated =
            CircuitBreaker.decorateSupplier(circuitBreaker, () -> executeRaw(config, identity));
        try {
            final RateLimitDecision decision = decorated.get();
            final String label = decision.allowed()
                ? PrometheusMetricsCollector.RESULT_ALLOWED
                : PrometheusMetricsCollector.RESULT_DENIED;
            return new DecisionOutcome(decision, label, null);
        } catch (CallNotPermittedException ex) {
            LOG.warn("Rate-limit circuit open for path={} — failing open", identity.endpoint());
            return new DecisionOutcome(RateLimitDecision.allow(0L),
                PrometheusMetricsCollector.RESULT_FAILOPEN,
                PrometheusMetricsCollector.REASON_CIRCUIT_OPEN);
        } catch (RuntimeException ex) {
            LOG.warn("Redis error for path={} — failing open: {}",
                identity.endpoint(), ex.getMessage());
            return new DecisionOutcome(RateLimitDecision.allow(0L),
                PrometheusMetricsCollector.RESULT_FAILOPEN,
                PrometheusMetricsCollector.REASON_REDIS_ERROR);
        }
    }
}
