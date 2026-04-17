package com.fluxguard.api;

import com.fluxguard.config.ConfigService;
import com.fluxguard.config.LimitConfig;
import com.fluxguard.model.LimitConfigRequest;
import jakarta.validation.Valid;
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
 * Admin REST API for dynamic rate-limit configuration and kill-switch management.
 *
 * <p>All mutations take effect immediately across all instances because
 * {@link ConfigService} is backed by the shared Redis cluster (ADR-004).
 *
 * <p>Security (authentication + authorisation) is deferred to Week 11.
 * These endpoints are currently unauthenticated.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final String ALGO_TOKEN_BUCKET   = "token_bucket";
    private static final String ALGO_SLIDING_WINDOW = "sliding_window";

    private final ConfigService configService;

    /**
     * Constructs the controller with its config service dependency.
     *
     * @param configService runtime config store
     */
    public AdminController(final ConfigService configService) {
        this.configService = configService;
    }

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
     * @return 200 on success, 400 if the request is invalid
     */
    @PostMapping("/configs")
    public ResponseEntity<Void> putConfig(
            @RequestParam final String endpoint,
            @RequestBody @Valid final LimitConfigRequest req) {
        final LimitConfig config = toConfig(endpoint, req);
        if (config == null) {
            return ResponseEntity.badRequest().build();
        }
        configService.putConfig(endpoint, config);
        return ResponseEntity.ok().build();
    }

    /**
     * Removes the rate-limit config for the given endpoint.
     *
     * <p>No-op if the endpoint is not configured.
     *
     * @param endpoint exact HTTP request URI to remove
     * @return 204 No Content
     */
    @DeleteMapping("/configs")
    public ResponseEntity<Void> removeConfig(@RequestParam final String endpoint) {
        configService.removeConfig(endpoint);
        return ResponseEntity.noContent().build();
    }

    /**
     * Activates the global kill switch — all rate limiting is immediately disabled.
     *
     * @return 200 OK
     */
    @PostMapping("/kill-switch/activate")
    public ResponseEntity<Void> activateKillSwitch() {
        configService.setKillSwitch(true);
        return ResponseEntity.ok().build();
    }

    /**
     * Deactivates the global kill switch — rate limiting resumes normally.
     *
     * @return 200 OK
     */
    @PostMapping("/kill-switch/deactivate")
    public ResponseEntity<Void> deactivateKillSwitch() {
        configService.setKillSwitch(false);
        return ResponseEntity.ok().build();
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Converts a validated request body into a {@link LimitConfig}.
     *
     * @param endpoint the target endpoint path
     * @param req      the incoming request body
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
}
