package com.fluxguard.api;

import com.fluxguard.config.ConfigService;
import com.fluxguard.config.FeatureFlagService;
import com.fluxguard.config.LimitConfig;
import com.fluxguard.model.FeatureFlag;
import com.fluxguard.model.FeatureFlagRequest;
import com.fluxguard.model.LimitConfigRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST API for dynamic rate-limit configuration, feature flag management,
 * and kill-switch control.
 *
 * <p>All mutations take effect immediately across all instances because
 * {@link ConfigService} and {@link FeatureFlagService} are backed by the shared
 * Redis cluster (ADR-004, ADR-005).
 *
 * <p>Authentication is enforced by {@link com.fluxguard.filter.AdminAuthFilter}
 * (ADR-006) — callers must supply the {@code X-Admin-Api-Key} header.
 * All mutations are recorded via {@link AuditService}.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final String ALGO_TOKEN_BUCKET   = "token_bucket";
    private static final String ALGO_SLIDING_WINDOW = "sliding_window";
    private static final int    MAX_AUDIT_LIMIT     = 1000;
    private static final int    ROLLOUT_MIN         = 0;
    private static final int    ROLLOUT_MAX         = 100;

    private final ConfigService      configService;
    private final AuditService       auditService;
    private final FeatureFlagService flagService;

    /**
     * Constructs the controller with its dependencies.
     *
     * @param configService runtime config store
     * @param auditService  records admin mutation events
     * @param flagService   runtime feature flag store
     */
    public AdminController(
            final ConfigService configService,
            final AuditService auditService,
            final FeatureFlagService flagService) {
        this.configService = configService;
        this.auditService  = auditService;
        this.flagService   = flagService;
    }

    // ── rate-limit config endpoints ──────────────────────────────────────────

    /**
     * Returns all currently configured per-endpoint rate limits.
     *
     * @return 200 with a map of endpoint path to limit config
     */
    @GetMapping("/configs")
    public ResponseEntity<Map<String, LimitConfig>> getAllConfigs() {
        return ResponseEntity.ok(configService.getAllConfigs());
    }

    /**
     * Creates or replaces the rate-limit config for the given endpoint.
     *
     * <p>The {@code endpoint} query parameter is the exact HTTP request URI
     * (e.g. {@code /api/search}). Required fields depend on the algorithm:
     * {@code token_bucket} needs {@code capacity} and {@code refillRatePerSecond};
     * {@code sliding_window} needs {@code limit} and {@code windowMs}.
     *
     * @param endpoint exact HTTP request URI to configure
     * @param req      algorithm type and parameters
     * @param request  injected HTTP request for actor resolution
     * @return 200 on success, 400 if the request is invalid
     */
    @PostMapping("/configs")
    public ResponseEntity<Void> putConfig(
            @RequestParam final String endpoint,
            @RequestBody @Valid final LimitConfigRequest req,
            final HttpServletRequest request) {
        final LimitConfig config = toConfig(endpoint, req);
        if (config == null) {
            return ResponseEntity.badRequest().build();
        }
        configService.putConfig(endpoint, config);
        auditService.record("PUT_CONFIG", endpoint, req.algorithm(), actor(request));
        return ResponseEntity.ok().build();
    }

    /**
     * Removes the rate-limit config for the given endpoint.
     *
     * <p>No-op if the endpoint is not configured.
     *
     * @param endpoint exact HTTP request URI to remove
     * @param request  injected HTTP request for actor resolution
     * @return 204 No Content
     */
    @DeleteMapping("/configs")
    public ResponseEntity<Void> removeConfig(
            @RequestParam final String endpoint,
            final HttpServletRequest request) {
        configService.removeConfig(endpoint);
        auditService.record("REMOVE_CONFIG", endpoint, "removed", actor(request));
        return ResponseEntity.noContent().build();
    }

    // ── feature flag endpoints ───────────────────────────────────────────────

    /**
     * Returns all currently stored feature flags.
     *
     * @return 200 with a map of endpoint path to feature flag
     */
    @GetMapping("/flags")
    public ResponseEntity<Map<String, FeatureFlag>> getAllFlags() {
        return ResponseEntity.ok(flagService.getAllFlags());
    }

    /**
     * Creates or replaces the feature flag for the given endpoint.
     *
     * <p>{@code rolloutPercent} must be in the range 0–100. {@code algorithm} is optional;
     * when provided, the matching capacity/rate/limit/window fields must be valid or the
     * request is rejected with 400.
     *
     * @param endpoint exact HTTP request URI to configure
     * @param req      flag settings and optional override algorithm
     * @param request  injected HTTP request for actor resolution
     * @return 200 on success, 400 if the request is invalid
     */
    @PostMapping("/flags")
    public ResponseEntity<Void> putFlag(
            @RequestParam final String endpoint,
            @RequestBody @Valid final FeatureFlagRequest req,
            final HttpServletRequest request) {
        if (req.rolloutPercent() < ROLLOUT_MIN || req.rolloutPercent() > ROLLOUT_MAX) {
            return ResponseEntity.badRequest().build();
        }
        final String algo = req.algorithm();
        LimitConfig override = null;
        if (algo != null && !algo.isBlank()) {
            override = toFlagConfig(endpoint, req);
            if (override == null) {
                return ResponseEntity.badRequest().build();
            }
        }
        final FeatureFlag flag = new FeatureFlag(
                endpoint, req.enabled(), req.darkLaunch(), req.rolloutPercent(), override);
        flagService.putFlag(endpoint, flag);
        auditService.record("PUT_FLAG", endpoint,
                algo != null && !algo.isBlank() ? algo : "no_override", actor(request));
        return ResponseEntity.ok().build();
    }

    /**
     * Removes the feature flag for the given endpoint.
     *
     * <p>No-op if the endpoint has no flag stored.
     *
     * @param endpoint exact HTTP request URI to remove
     * @param request  injected HTTP request for actor resolution
     * @return 204 No Content
     */
    @DeleteMapping("/flags")
    public ResponseEntity<Void> removeFlag(
            @RequestParam final String endpoint,
            final HttpServletRequest request) {
        flagService.removeFlag(endpoint);
        auditService.record("REMOVE_FLAG", endpoint, "removed", actor(request));
        return ResponseEntity.noContent().build();
    }

    // ── kill-switch endpoints ────────────────────────────────────────────────

    /**
     * Activates the global kill switch — all rate limiting is immediately disabled.
     *
     * @param request injected HTTP request for actor resolution
     * @return 200 OK
     */
    @PostMapping("/kill-switch/activate")
    public ResponseEntity<Void> activateKillSwitch(final HttpServletRequest request) {
        configService.setKillSwitch(true);
        auditService.record("ACTIVATE_KILL_SWITCH", "global", "kill switch activated",
                actor(request));
        return ResponseEntity.ok().build();
    }

    /**
     * Deactivates the global kill switch — rate limiting resumes normally.
     *
     * @param request injected HTTP request for actor resolution
     * @return 200 OK
     */
    @PostMapping("/kill-switch/deactivate")
    public ResponseEntity<Void> deactivateKillSwitch(final HttpServletRequest request) {
        configService.setKillSwitch(false);
        auditService.record("DEACTIVATE_KILL_SWITCH", "global", "kill switch deactivated",
                actor(request));
        return ResponseEntity.ok().build();
    }

    // ── audit endpoint ───────────────────────────────────────────────────────

    /**
     * Returns recent audit log entries, oldest first.
     *
     * <p>The {@code limit} parameter is capped at {@value #MAX_AUDIT_LIMIT} to prevent abuse.
     *
     * @param limit maximum number of entries to return (default 100, max 1000)
     * @return 200 with a list of JSON audit strings
     */
    @GetMapping("/audit")
    public ResponseEntity<List<String>> getAuditLog(
            @RequestParam(defaultValue = "100") final int limit) {
        return ResponseEntity.ok(auditService.getRecent(Math.min(limit, MAX_AUDIT_LIMIT)));
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Extracts the actor identity set by {@link com.fluxguard.filter.AdminAuthFilter}.
     *
     * @param request the current HTTP request
     * @return the actor string, or {@code "unknown"} if the attribute is absent
     */
    private static String actor(final HttpServletRequest request) {
        final Object attr = request.getAttribute("X-Admin-Actor");
        return attr != null ? attr.toString() : "unknown";
    }

    /**
     * Converts a {@link LimitConfigRequest} into a {@link LimitConfig}.
     *
     * @param endpoint the target endpoint path
     * @param req      the incoming config request body
     * @return a {@link LimitConfig}, or {@code null} if required fields are missing/invalid
     */
    private static LimitConfig toConfig(final String endpoint, final LimitConfigRequest req) {
        if (ALGO_TOKEN_BUCKET.equals(req.algorithm())) {
            if (req.capacity() == null || req.capacity() <= 0
                    || req.refillRatePerSecond() == null || req.refillRatePerSecond() <= 0) {
                return null;
            }
            return LimitConfig.tokenBucket(endpoint, req.capacity(), req.refillRatePerSecond());
        }
        if (ALGO_SLIDING_WINDOW.equals(req.algorithm())) {
            if (req.limit() == null || req.limit() <= 0
                    || req.windowMs() == null || req.windowMs() <= 0) {
                return null;
            }
            return LimitConfig.slidingWindow(endpoint, req.limit(), req.windowMs());
        }
        return null;
    }

    /**
     * Converts a {@link FeatureFlagRequest} override algorithm into a {@link LimitConfig}.
     *
     * @param endpoint the target endpoint path
     * @param req      the incoming flag request body (algorithm field must be non-blank)
     * @return a {@link LimitConfig}, or {@code null} if the algorithm is unknown or
     *         the required fields are missing/invalid
     */
    private static LimitConfig toFlagConfig(final String endpoint, final FeatureFlagRequest req) {
        if (ALGO_TOKEN_BUCKET.equals(req.algorithm())) {
            if (req.capacity() == null || req.capacity() <= 0
                    || req.refillRatePerSecond() == null || req.refillRatePerSecond() <= 0) {
                return null;
            }
            return LimitConfig.tokenBucket(endpoint, req.capacity(), req.refillRatePerSecond());
        }
        if (ALGO_SLIDING_WINDOW.equals(req.algorithm())) {
            if (req.limit() == null || req.limit() <= 0
                    || req.windowMs() == null || req.windowMs() <= 0) {
                return null;
            }
            return LimitConfig.slidingWindow(endpoint, req.limit(), req.windowMs());
        }
        return null;
    }
}
