package com.fluxguard.filter;

import com.fluxguard.config.ConfigService;
import com.fluxguard.config.FeatureFlagService;
import com.fluxguard.config.LimitConfig;
import com.fluxguard.engine.DecisionOutcome;
import com.fluxguard.engine.RateLimitEngine;
import com.fluxguard.metrics.PrometheusMetricsCollector;
import com.fluxguard.model.ClientIdentity;
import com.fluxguard.model.FeatureFlag;
import com.fluxguard.model.RateLimitDecision;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
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
 *   <li>If the global kill switch is active, allow immediately (no Redis call).</li>
 *   <li>Reject with 400 if {@code X-Client-ID} header is absent or blank.</li>
 *   <li>Skip limiting if no {@link LimitConfig} is registered for the exact path.</li>
 *   <li>Apply feature-flag logic: live rollout, dark launch, or primary config.</li>
 *   <li>Delegate the decision to {@link RateLimitEngine}, which executes the Lua
 *       rate-limit script wrapped in a Resilience4j circuit breaker.</li>
 *   <li>On allow: set {@code X-RateLimit-Remaining} and continue.</li>
 *   <li>On deny: return HTTP 429 with {@code Retry-After} (seconds).</li>
 *   <li>On Redis failure or circuit open: fail-open (allow + log WARN + counter).</li>
 * </ol>
 *
 * <p>The actual Redis call lives in {@link RateLimitEngine}; this filter only adapts
 * the engine's {@link DecisionOutcome} onto the servlet response.
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

    /** Client ID suffix appended to the shadow bucket key in dark-launch mode. */
    private static final String DARK_LAUNCH_SUFFIX = ":dark";

    private static final int  STATUS_BAD_REQUEST       = 400;
    private static final int  STATUS_TOO_MANY_REQUESTS = 429;
    private static final long MILLIS_PER_SECOND        = 1000L;

    private final RateLimitEngine engine;
    private final ConfigService configService;
    private final FeatureFlagService flagService;
    private final PrometheusMetricsCollector metrics;

    /**
     * Constructs the filter with all required collaborators.
     *
     * @param engine        the transport-agnostic rate-limit decision core
     * @param configService dynamic per-endpoint rate-limit configuration
     * @param flagService   per-endpoint feature flag store
     * @param metrics       Micrometer metrics facade for lookup-path fail-open signals
     */
    public RateLimitFilter(
            final RateLimitEngine engine,
            final ConfigService configService,
            final FeatureFlagService flagService,
            final PrometheusMetricsCollector metrics) {
        this.engine = engine;
        this.configService = configService;
        this.flagService = flagService;
        this.metrics = metrics;
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
        final String path = request.getRequestURI();
        final LimitConfig config;
        try {
            if (configService.isKillSwitchActive()) {
                return true;
            }
            final Optional<LimitConfig> configOpt = configService.getConfig(path);
            if (configOpt.isEmpty()) {
                return true;
            }
            config = configOpt.get();
        } catch (RuntimeException ex) {
            // Kill-switch / config reads are not behind the circuit breaker; a Redis
            // failure here must fail open rather than surface as a 500.
            return failOpenOnLookup(path, ex);
        }
        final String clientId = request.getHeader(HEADER_CLIENT_ID);
        if (clientId == null || clientId.isBlank()) {
            response.setStatus(STATUS_BAD_REQUEST);
            return false;
        }
        return applyWithFlag(path, clientId, config, response);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private boolean failOpenOnLookup(final String path, final RuntimeException ex) {
        LOG.warn("Config/kill-switch lookup failed for path={} — failing open: {}",
            path, ex.getMessage());
        metrics.recordFailOpen(path, PrometheusMetricsCollector.REASON_CONFIG_ERROR);
        return true;
    }

    private boolean applyWithFlag(
            final String path,
            final String clientId,
            final LimitConfig primaryConfig,
            final HttpServletResponse response) {
        final Optional<FeatureFlag> flagOpt;
        try {
            flagOpt = flagService.getFlagForEndpoint(path);
        } catch (RuntimeException ex) {
            // The flag read is a Redis call outside the circuit breaker. If it fails we
            // still enforce the primary limit (the decision path has its own fail-open),
            // rather than dropping the flag's existence into a 500.
            LOG.warn("Flag lookup failed for path={} — enforcing primary config: {}",
                path, ex.getMessage());
            return executeAndApply(path, clientId, primaryConfig, response);
        }
        if (flagOpt.isEmpty() || !flagOpt.get().enabled()) {
            return executeAndApply(path, clientId, primaryConfig, response);
        }
        final FeatureFlag flag = flagOpt.get();
        if (!flagService.isClientInRollout(flag, clientId)) {
            return executeAndApply(path, clientId, primaryConfig, response);
        }
        if (flag.darkLaunch()) {
            runDarkLaunchShadow(path, clientId, flag.overrideConfig());
            return executeAndApply(path, clientId, primaryConfig, response);
        }
        return executeAndApply(path, clientId, flag.overrideConfig(), response);
    }

    private void runDarkLaunchShadow(
            final String path,
            final String clientId,
            final LimitConfig overrideConfig) {
        try {
            final ClientIdentity shadowId =
                ClientIdentity.of(clientId + DARK_LAUNCH_SUFFIX, path);
            final RateLimitDecision shadowDecision = engine.executeRaw(overrideConfig, shadowId);
            if (!shadowDecision.allowed()) {
                metrics.recordDarkLaunchWouldDeny(path);
            }
        } catch (RuntimeException ex) {
            LOG.warn("Dark launch shadow failed for path={}: {}", path, ex.getMessage());
        }
    }

    private boolean executeAndApply(
            final String path,
            final String clientId,
            final LimitConfig config,
            final HttpServletResponse response) {
        final ClientIdentity identity = ClientIdentity.of(clientId, path);
        final DecisionOutcome outcome = engine.decide(config, identity);
        return applyDecision(outcome.decision(), response);
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
}
