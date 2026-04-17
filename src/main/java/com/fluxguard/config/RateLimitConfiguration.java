package com.fluxguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxguard.api.AuditService;
import com.fluxguard.api.RedisAuditService;
import com.fluxguard.filter.AdminAuthFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Spring configuration for rate-limiting infrastructure beans.
 *
 * <p>Provides the {@link Map} of endpoint-to-algorithm bindings injected into
 * {@code RateLimitFilter}, and the {@link CircuitBreaker} that wraps Redis calls.
 *
 * <p>The endpoint map uses exact-path matching. Dynamic reconfiguration
 * (loading limits from a config store at runtime) is planned for Month 3;
 * until then, limits are declared statically here.
 */
@Configuration
public class RateLimitConfiguration {

    /** Circuit-breaker instance name; must match the key in {@code application.yml}. */
    private static final String CIRCUIT_BREAKER_NAME = "redis-rate-limit";

    /** Maximum requests allowed per 60-second sliding window on the search endpoint. */
    private static final long SEARCH_LIMIT = 100L;

    /** Sliding-window duration for the search endpoint in milliseconds (60 seconds). */
    private static final long SEARCH_WINDOW_MS = 60_000L;

    /** Token-bucket capacity for the ingest endpoint. */
    private static final long INGEST_CAPACITY = 50L;

    /** Token refill rate for the ingest endpoint in tokens per second. */
    private static final long INGEST_REFILL_RATE = 10L;

    /**
     * Provides the per-endpoint rate-limit configuration map.
     *
     * <p>Keys are exact HTTP request URIs. {@code RateLimitFilter} skips limiting
     * for any path not present in this map.
     *
     * @return immutable map of endpoint path to {@link LimitConfig}
     */
    @Bean
    public Map<String, LimitConfig> rateLimitConfigByPath() {
        return Map.of(
            "/api/search", LimitConfig.slidingWindow("/api/search", SEARCH_LIMIT, SEARCH_WINDOW_MS),
            "/api/ingest", LimitConfig.tokenBucket("/api/ingest", INGEST_CAPACITY, INGEST_REFILL_RATE)
        );
    }

    /**
     * Provides the {@link ConfigService} backed by Redis.
     *
     * <p>This is the sole registration point for {@link RedisConfigService} —
     * the implementation carries no {@code @Component} annotation (ADR-004).
     *
     * @param redis        Spring Data Redis string template
     * @param objectMapper Jackson mapper auto-configured by Spring Boot
     * @return the Redis-backed config service
     */
    @Bean
    public ConfigService configService(
            final StringRedisTemplate redis,
            final ObjectMapper objectMapper) {
        return new RedisConfigService(redis, objectMapper);
    }

    /**
     * Seeds Redis with the static config map on startup, skipping keys already present.
     *
     * <p>Idempotent across restarts. Allows operators to override limits at runtime
     * without having them reset on redeploy.
     *
     * @param configService      the Redis-backed config service
     * @param rateLimitConfigByPath static endpoint-to-algorithm bindings
     * @return a runner that seeds missing keys
     */
    @Bean
    public CommandLineRunner seedRedisConfigs(
            final ConfigService configService,
            final Map<String, LimitConfig> rateLimitConfigByPath) {
        return args -> rateLimitConfigByPath.forEach((path, cfg) -> {
            if (configService.getConfig(path).isEmpty()) {
                configService.putConfig(path, cfg);
            }
        });
    }

    /**
     * Provides the Resilience4j circuit breaker that guards Redis calls.
     *
     * <p>Configuration (thresholds, window size, wait duration) is read from
     * {@code resilience4j.circuitbreaker.instances.redis-rate-limit} in
     * {@code application.yml}.
     *
     * @param registry auto-configured Resilience4j registry
     * @return the named circuit-breaker instance
     */
    @Bean
    public CircuitBreaker redisCircuitBreaker(final CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(CIRCUIT_BREAKER_NAME);
    }

    /**
     * Provides the {@link FeatureFlagService} backed by Redis.
     *
     * <p>This is the sole registration point for {@link RedisFeatureFlagService} —
     * the implementation carries no {@code @Component} annotation (ADR-005).
     *
     * @param redis        Spring Data Redis string template
     * @param objectMapper Jackson mapper auto-configured by Spring Boot
     * @return the Redis-backed feature flag service
     */
    @Bean
    public FeatureFlagService featureFlagService(
            final StringRedisTemplate redis,
            final ObjectMapper objectMapper) {
        return new RedisFeatureFlagService(redis, objectMapper);
    }

    /**
     * Provides the {@link AdminAuthFilter} that enforces API-key authentication on
     * {@code /admin/**} endpoints.
     *
     * <p>This is the sole registration point — the implementation carries no
     * {@code @Component} annotation (ADR-006). {@code @Value} resolution for the key
     * lives here so that {@link AdminAuthFilter} remains testable without a Spring context.
     *
     * @param apiKey the expected API key, resolved from {@code fluxguard.admin.api-key}
     * @return the admin auth interceptor
     */
    @Bean
    public AdminAuthFilter adminAuthFilter(
            @Value("${fluxguard.admin.api-key}") final String apiKey) {
        return new AdminAuthFilter(apiKey);
    }

    /**
     * Provides the {@link AuditService} backed by Redis.
     *
     * <p>This is the sole registration point for {@link RedisAuditService} —
     * the implementation carries no {@code @Component} annotation (ADR-006).
     *
     * @param redis        Spring Data Redis string template
     * @param objectMapper Jackson mapper auto-configured by Spring Boot
     * @return the Redis-backed audit service
     */
    @Bean
    public AuditService auditService(
            final StringRedisTemplate redis,
            final ObjectMapper objectMapper) {
        return new RedisAuditService(redis, objectMapper);
    }

    /**
     * Provides the application tracer used for rate-limit decision and Redis script spans.
     *
     * @param openTelemetry auto-configured OpenTelemetry entrypoint from the starter
     * @return tracer scoped to the FluxGuard application
     */
    @Bean
    public Tracer tracer(final OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("fluxguard");
    }
}
