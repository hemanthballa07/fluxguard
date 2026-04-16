package com.fluxguard.filter;

import com.fluxguard.config.LimitConfig;
import com.fluxguard.model.ClientIdentity;
import com.fluxguard.model.RateLimitDecision;
import com.fluxguard.redis.LuaScriptExecutor;
import com.fluxguard.util.ClockProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    /** Micrometer counter name for fail-open events; tags distinguish reason. */
    private static final String COUNTER_FAIL_OPEN = "redis.failopen.total";

    /** Tag key used to distinguish fail-open trigger reasons. */
    private static final String TAG_REASON = "reason";

    /** Tag value when a {@link RuntimeException} from Redis triggers fail-open. */
    private static final String REASON_REDIS_ERROR = "redis_error";

    /** Tag value when an open circuit breaker triggers fail-open. */
    private static final String REASON_CIRCUIT_OPEN = "circuit_open";

    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final long MILLIS_PER_SECOND = 1000L;

    private final LuaScriptExecutor executor;
    private final Map<String, LimitConfig> configByPath;
    private final ClockProvider clock;
    private final CircuitBreaker circuitBreaker;
    private final Counter redisErrorCounter;
    private final Counter circuitOpenCounter;

    /**
     * Constructs the filter with all required collaborators.
     *
     * @param executor        executes Lua scripts against Redis
     * @param configByPath    exact-path-to-algorithm bindings
     * @param clock           injectable clock; never call {@code System.currentTimeMillis()} directly
     * @param circuitBreaker  Resilience4j circuit breaker wrapping Redis calls
     * @param meterRegistry   Micrometer registry for fail-open counters
     */
    public RateLimitFilter(
            final LuaScriptExecutor executor,
            final Map<String, LimitConfig> configByPath,
            final ClockProvider clock,
            final CircuitBreaker circuitBreaker,
            final MeterRegistry meterRegistry) {
        this.executor = executor;
        this.configByPath = configByPath;
        this.clock = clock;
        this.circuitBreaker = circuitBreaker;
        this.redisErrorCounter = Counter.builder(COUNTER_FAIL_OPEN)
            .tag(TAG_REASON, REASON_REDIS_ERROR)
            .description("Requests allowed due to Redis unavailability")
            .register(meterRegistry);
        this.circuitOpenCounter = Counter.builder(COUNTER_FAIL_OPEN)
            .tag(TAG_REASON, REASON_CIRCUIT_OPEN)
            .description("Requests allowed because the circuit breaker is open")
            .register(meterRegistry);
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
        final ClientIdentity identity = ClientIdentity.of(clientId, path);
        final RateLimitDecision decision = executeWithCircuitBreaker(identity, config);
        return applyDecision(decision, response);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private RateLimitDecision executeWithCircuitBreaker(
            final ClientIdentity identity,
            final LimitConfig config) {
        final Supplier<RateLimitDecision> decorated =
            CircuitBreaker.decorateSupplier(circuitBreaker, () -> callExecutor(identity, config));
        try {
            return decorated.get();
        } catch (CallNotPermittedException ex) {
            LOG.warn("Rate-limit circuit open for path={} — failing open", identity.endpoint());
            circuitOpenCounter.increment();
            return RateLimitDecision.allow(0L);
        } catch (RuntimeException ex) {
            LOG.warn("Redis error for path={} — failing open: {}", identity.endpoint(),
                ex.getMessage());
            redisErrorCounter.increment();
            return RateLimitDecision.allow(0L);
        }
    }

    private RateLimitDecision callExecutor(
            final ClientIdentity identity,
            final LimitConfig config) {
        final long now = clock.nowMillis();
        final List<String> keys = config.algorithm().buildLuaKeys(identity.bucketKey(), now);
        final List<String> args = config.algorithm().buildLuaArgs(now);
        final List<Object> result = executor.execute(config.algorithm().luaScriptName(), keys, args);
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
        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(ceilSeconds(decision.resetAfterMs())));
        return false;
    }

    private long ceilSeconds(final long ms) {
        return (long) Math.ceil((double) ms / MILLIS_PER_SECOND);
    }
}
