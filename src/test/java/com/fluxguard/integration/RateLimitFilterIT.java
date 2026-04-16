package com.fluxguard.integration;

import com.fluxguard.config.LimitConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link com.fluxguard.filter.RateLimitFilter}.
 *
 * <p>Spins up a full Spring Boot application context with a real Redis instance
 * via Testcontainers, and sends HTTP requests via {@link TestRestTemplate} to
 * verify the filter's behaviour end-to-end.
 *
 * <p>A {@link TestConfiguration} overrides the production {@code rateLimitConfigByPath}
 * bean with a low limit (3 req / 60 s) on {@code /api/it-test} so boundary conditions
 * can be exercised quickly.
 *
 * <p>Naming convention: {@code IT} suffix causes Maven Failsafe to include this class
 * in the integration-test phase ({@code mvn verify}), not the unit-test phase
 * ({@code mvn test}).
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.main.allow-bean-definition-overriding=true"
)
class RateLimitFilterIT {

    private static final int REDIS_PORT = 6379;

    /** Low limit that lets us exercise deny without firing many requests. */
    private static final long IT_LIMIT = 3L;

    /** Window duration — long enough that keys don't expire during the test. */
    private static final long IT_WINDOW_MS = 60_000L;

    /** The path registered in the test config map. */
    private static final String IT_PATH = "/api/it-test";

    /** A path not registered in the test config map — must pass through unlimted. */
    private static final String UNKNOWN_PATH = "/api/it-unknown";

    /** Header used to identify clients in the filter. */
    private static final String CLIENT_ID_HEADER = "X-Client-ID";

    /** Header set on allowed responses. */
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    /** Header set on denied responses. */
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    @Container
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(REDIS_PORT);

    /**
     * Injects the Testcontainers Redis host/port into the Spring context before
     * it starts, overriding the values from {@code application.yml}.
     *
     * @param reg dynamic property registry provided by Spring Test
     */
    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry reg) {
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    /**
     * Test-only configuration that replaces the production rate-limit map with a
     * low-limit endpoint ({@code /api/it-test}) so boundary conditions can be
     * exercised without firing hundreds of requests.
     *
     * <p>Also exposes a minimal {@link RestController} so the filter has a real
     * controller to delegate to on allowed requests.
     */
    @TestConfiguration
    static class ItConfig {

        /**
         * Overrides the production {@code rateLimitConfigByPath} bean.
         *
         * @return test-specific config map with a 3 req / 60 s sliding-window limit
         */
        @Bean
        @Primary
        public Map<String, LimitConfig> rateLimitConfigByPath() {
            return Map.of(
                IT_PATH, LimitConfig.slidingWindow(IT_PATH, IT_LIMIT, IT_WINDOW_MS)
            );
        }

        /**
         * Minimal controller so the filter has a real endpoint to dispatch to.
         */
        @RestController
        static class ItController {

            /**
             * Returns 200 OK; used to verify that allowed requests reach the controller.
             *
             * @return plain-text OK response
             */
            @GetMapping(IT_PATH)
            public String handle() {
                return "ok";
            }

            /**
             * Returns 200 OK on a path not present in the rate-limit config.
             *
             * @return plain-text OK response
             */
            @GetMapping(UNKNOWN_PATH)
            public String handleUnknown() {
                return "ok";
            }
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Verifies that exactly {@code limit} requests within a fresh window are all
     * allowed and that {@code X-RateLimit-Remaining} decrements correctly.
     */
    @Test
    void exactLimitAllowsAllRequestsWithDecreasingRemaining() {
        final String clientId = "it-exact-" + System.nanoTime();

        for (long i = IT_LIMIT - 1; i >= 0; i--) {
            final ResponseEntity<String> resp = sendRequest(IT_PATH, clientId);
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "Request must be allowed within limit");
            assertEquals(String.valueOf(i), resp.getHeaders().getFirst(REMAINING_HEADER),
                "Remaining must decrement correctly");
        }
    }

    /**
     * Verifies that the (limit+1)th request within a window is denied with HTTP 429
     * and a positive {@code Retry-After} header.
     */
    @Test
    void limitPlusOneReturns429WithRetryAfterHeader() {
        final String clientId = "it-overflow-" + System.nanoTime();

        for (int i = 0; i < IT_LIMIT; i++) {
            sendRequest(IT_PATH, clientId);
        }

        final ResponseEntity<String> overflow = sendRequest(IT_PATH, clientId);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, overflow.getStatusCode(),
            "Request beyond limit must be denied");
        assertNotNull(overflow.getHeaders().getFirst(RETRY_AFTER_HEADER),
            "Denied response must carry Retry-After header");
    }

    /**
     * Verifies that a missing {@code X-Client-ID} header causes a 400 response
     * and the filter short-circuits before calling Redis.
     */
    @Test
    void missingClientIdHeaderReturnsBadRequest() {
        final ResponseEntity<String> resp = restTemplate.exchange(
            IT_PATH, HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    /**
     * Verifies that a path not in the rate-limit config passes through unlimted.
     */
    @Test
    void unknownPathPassesThroughWithoutLimiting() {
        final ResponseEntity<String> resp = sendRequest(UNKNOWN_PATH, "any-client");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private ResponseEntity<String> sendRequest(final String path, final String clientId) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set(CLIENT_ID_HEADER, clientId);
        return restTemplate.exchange(path, HttpMethod.GET,
            new HttpEntity<>(headers), String.class);
    }
}
