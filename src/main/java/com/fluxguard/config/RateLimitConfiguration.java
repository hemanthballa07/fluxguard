package com.fluxguard.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
