package com.fluxguard.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link com.fluxguard.filter.AdminAuthFilter}.
 *
 * <p>Spins up a full Spring Boot application context with a real Redis instance via
 * Testcontainers, and verifies end-to-end that {@code /admin/**} endpoints are
 * protected by the API-key interceptor registered in {@code WebMvcConfig}.
 *
 * <p>The test API key is injected via {@code @SpringBootTest properties} so it is
 * known at compile time and never touches a real secret.
 *
 * <p>Naming convention: {@code IT} suffix causes Maven Failsafe to include this class
 * in the integration-test phase ({@code mvn verify}), not the unit-test phase
 * ({@code mvn test}).
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "fluxguard.admin.api-key=it-test-secret"
    }
)
class AdminAuthFilterIT {

    private static final int    REDIS_PORT     = 6379;
    private static final String API_KEY        = "it-test-secret";
    private static final String WRONG_KEY      = "wrong-key";
    private static final String HEADER_API_KEY = "X-Admin-Api-Key";

    @Container
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(REDIS_PORT);

    /**
     * Wires the Testcontainers Redis host and port into the Spring context before
     * startup, overriding the values from {@code application.yml}.
     *
     * @param reg dynamic property registry provided by Spring Test
     */
    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry reg) {
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Verifies that {@code GET /admin/configs} returns 401 when the API-key header
     * is absent.
     */
    @Test
    void missingApiKeyReturns401OnConfigs() {
        final ResponseEntity<String> resp = restTemplate.exchange(
            "/admin/configs", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    /**
     * Verifies that {@code GET /admin/configs} returns 401 when an incorrect key is
     * supplied — protects against guessing attacks.
     */
    @Test
    void wrongApiKeyReturns401OnConfigs() {
        final ResponseEntity<String> resp = restTemplate.exchange(
            "/admin/configs", HttpMethod.GET, withKey(WRONG_KEY), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    /**
     * Verifies that {@code GET /admin/configs} returns 200 when the correct key is
     * supplied — proves the success path reaches the controller.
     */
    @Test
    void correctKeyAllowsGetConfigs() {
        final ResponseEntity<String> resp = restTemplate.exchange(
            "/admin/configs", HttpMethod.GET, withKey(API_KEY), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    /**
     * Verifies the {@code GET /admin/configs} response body contains the expected JSON
     * shape: each entry must have an {@code endpointPattern} string and an {@code algorithm}
     * object with a {@code type} discriminator field (e.g. {@code "sliding_window"} or
     * {@code "token_bucket"}).
     *
     * <p>Example: {@code {"/api/search":{"endpointPattern":"/api/search",
     * "algorithm":{"type":"sliding_window","limit":100,"windowMs":60000}}}}
     */
    @Test
    void correctKeyGetConfigsResponseBodyContainsAlgorithmShape() {
        final ResponseEntity<String> resp = restTemplate.exchange(
            "/admin/configs", HttpMethod.GET, withKey(API_KEY), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        final String body = resp.getBody();
        assertNotNull(body, "Response body must not be null");
        assertTrue(body.contains("\"endpointPattern\""),
            "Response must contain endpointPattern field");
        assertTrue(body.contains("\"type\""),
            "Response must contain algorithm type discriminator");
    }

    /**
     * Verifies that the filter's {@code /admin/**} pattern covers {@code /admin/flags},
     * not just {@code /admin/configs} — rejects missing key on a different sub-path.
     */
    @Test
    void missingApiKeyReturns401OnFlags() {
        final ResponseEntity<String> resp = restTemplate.exchange(
            "/admin/flags", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    /**
     * Verifies that {@code GET /admin/flags} returns 200 when the correct key is
     * supplied — proves the filter grants access across the full {@code /admin/**} scope.
     */
    @Test
    void correctKeyAllowsGetFlags() {
        final ResponseEntity<String> resp = restTemplate.exchange(
            "/admin/flags", HttpMethod.GET, withKey(API_KEY), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private HttpEntity<Void> withKey(final String key) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_API_KEY, key);
        return new HttpEntity<>(headers);
    }
}
