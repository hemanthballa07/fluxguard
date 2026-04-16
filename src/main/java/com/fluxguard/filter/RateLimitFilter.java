package com.fluxguard.filter;

import com.fluxguard.config.LimitConfig;
import com.fluxguard.metrics.PrometheusMetricsCollector;
import com.fluxguard.model.ClientIdentity;
import com.fluxguard.model.RateLimitDecision;
import com.fluxguard.redis.LuaScriptExecutor;
import com.fluxguard.util.ClockProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC interceptor that enforces per-client rate limits before each request
 * reaches a controller.
 *
 * <p>Execution order for every inbound request:
 * <ol>
 *   <li>Reject with 400 if {@code X-Client-ID} header is absent or blank.</li>
 *   <li>Skip limiting if no {@link LimitConfig} is registered for the exact path.</li>
 *   <li>Execute the Lua rate-limit script via {@link LuaScriptExecutor},
 *       wrapped in a Resilience4j circuit breaker.</li>
 *   <li>On allow: set {@code X-RateLimit-Remaining} and continue.</li>
 *   <li>On deny: return HTTP 429 with {@code Retry-After} (seconds).</li>
 *   <li>On Redis failure or circuit open: fail-open (allow + log WARN + counter).</li>
 * </ol>
 *
 * <p>Duration is recorded only when a real rate-limit decision was made (step 3).
 * The 400 path (step 1) and unknown-path pass-through (step 2) do not record duration.
 *
 * <p>This is the <em>only</em> class in the application that calls Redis for
 * rate-limiting decisions, per the architectural rule in CLAUDE.md.
 */
@Component
public class RateLimitFilter implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    /** HTTP request header carrying the client identifier for rate limiting. */
    private static final String HEADER_CLIENT_ID = "X-Client-ID";

    /** HTTP response header reporting remaining capacity after an allowed request. */
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";

    /** HTTP response header indicating when the client may retry after a 429. */
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    /** Span name used for the full rate-limit decision flow. */
    private static final String SPAN_RATE_LIMIT_DECISION = "rate_limit.decision";

    /** Span attribute key for the HTTP endpoint being rate limited. */
    private static final String TAG_ENDPOINT = "endpoint";

    /** Span attribute key for the algorithm used for the decision. */
    private static final String TAG_ALGORITHM = "algorithm";

    /** Span attribute key for the final result label. */
    private static final String TAG_RESULT = "result";

    /** Span attribute key for the fail-open reason when applicable. */
    private static final String TAG_FAIL_OPEN_REASON = "fail_open_reason";

    private static final int  STATUS_BAD_REQUEST       = 400;
    private static final int  STATUS_TOO_MANY_REQUESTS = 429;
    private static final long MILLIS_PER_SECOND        = 1000L;

    private final LuaScriptExecutor executor;
    private final Map<String, LimitConfig> configByPath;
    private final ClockProvider clock;
    private final CircuitBreaker circuitBreaker;
    private final PrometheusMetricsCollector metrics;
    private final Tracer tracer;

    /**
     * Constructs the filter with all required collaborators.
     *
     * @param executor        executes Lua scripts against Redis
     * @param configByPath    exact-path-to-algorithm bindings
     * @param clock           injectable clock; never call {@code System.currentTimeMillis()} directly
     * @param circuitBreaker  Resilience4j circuit breaker wrapping Redis calls
     * @param metrics         Micrometer metrics facade for all rate-limit signals
     * @param tracer          tracer used to create rate-limit decision spans
     */
    public RateLimitFilter(
            final LuaScriptExecutor executor,
            final Map<String, LimitConfig> configByPath,
            final ClockProvider clock,
            final CircuitBreaker circuitBreaker,
            final PrometheusMetricsCollector metrics,
            final Tracer tracer) {
        this.executor = executor;
        this.configByPath = configByPath;
        this.clock = clock;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    /**
     * Enforces the rate limit for the incoming request.
     *
     * <p>Returns {@code false} and commits a 400 or 429 response when the request
     * is rejected. Returns {@code true} to allow the request to proceed to the controller.
     *
     * @param request  current HTTP request
     * @param response current HTTP response
     * @param handler  chosen handler (unused)
     * @return {@code true} to continue processing; {@code false} if the request is rejected
     */
    @Override
    public boolean preHandle(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final Object handler) {
        final String clientId = request.getHeader(HEADER_CLIENT_ID);
        if (clientId == null || clientId.isBlank()) {
            response.setStatus(STATUS_BAD_REQUEST);
            return false;
        }
        final String path = request.getRequestURI();
        final LimitConfig config = configByPath.get(path);
        if (config == null) {
            return true;
        }
        return executeAndApply(path, clientId, config, response);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private boolean executeAndApply(
            final String path,
            final String clientId,
            final LimitConfig config,
            final HttpServletResponse response) {
        final ClientIdentity identity = ClientIdentity.of(clientId, path);
        final String algorithmName = config.algorithm().luaScriptName();
        final Span span = tracer.spanBuilder(SPAN_RATE_LIMIT_DECISION).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            final long startNs = System.nanoTime();
            final DecisionOutcome outcome = executeWithCircuitBreaker(identity, config);
            recordMetrics(path, algorithmName, outcome, System.nanoTime() - startNs);
            annotateSpan(span, path, algorithmName, outcome);
            return applyDecision(outcome.decision(), response);
        } finally {
            span.end();
        }
    }

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
            final String path,
            final String algorithmName,
            final DecisionOutcome outcome) {
        span.setAttribute(TAG_ENDPOINT, path);
        span.setAttribute(TAG_ALGORITHM, algorithmName);
        span.setAttribute(TAG_RESULT, outcome.resultLabel());
        if (outcome.failOpenReason() != null) {
            span.setAttribute(TAG_FAIL_OPEN_REASON, outcome.failOpenReason());
        }
        if (PrometheusMetricsCollector.REASON_REDIS_ERROR.equals(outcome.failOpenReason())) {
            span.setStatus(StatusCode.ERROR);
            return;
        }
        span.setStatus(StatusCode.OK);
    }

    private DecisionOutcome executeWithCircuitBreaker(
            final ClientIdentity identity,
            final LimitConfig config) {
        final Supplier<RateLimitDecision> decorated =
            CircuitBreaker.decorateSupplier(circuitBreaker, () -> callExecutor(identity, config));
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

    private RateLimitDecision callExecutor(
            final ClientIdentity identity,
            final LimitConfig config) {
        final long now = clock.nowMillis();
        final List<String> keys = config.algorithm().buildLuaKeys(identity.bucketKey(), now);
        final List<String> args = config.algorithm().buildLuaArgs(now);
        final List<Object> result =
            executor.execute(config.algorithm().luaScriptName(), keys, args);
        return config.algorithm().parseResult(result);
    }

    private boolean applyDecision(
            final RateLimitDecision decision,
            final HttpServletResponse response) {
        if (decision.allowed()) {
            final long remaining = decision.remainingTokens();
            if (remaining >= 0) {
                response.setHeader(HEADER_REMAINING, String.valueOf(remaining));
            }
            return true;
        }
        response.setStatus(STATUS_TOO_MANY_REQUESTS);
        response.setHeader(HEADER_RETRY_AFTER,
            String.valueOf(ceilSeconds(decision.resetAfterMs())));
        return false;
    }

    private long ceilSeconds(final long ms) {
        return (long) Math.ceil((double) ms / MILLIS_PER_SECOND);
    }

    /** Internal DTO carrying a rate-limit decision together with its metrics labels. */
    private record DecisionOutcome(
            RateLimitDecision decision,
            String resultLabel,
            String failOpenReason) { }
}
