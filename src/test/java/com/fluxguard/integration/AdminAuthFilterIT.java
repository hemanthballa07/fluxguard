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

import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Verifies that PUT /admin/flags without an algorithm override does not 500 —
     * regression guard for the serialize() NPE on null overrideConfig.
     */
    @Test
    void putFlagWithoutOverrideDoesNotReturn500() {
        final ResponseEntity<Void> resp = restTemplate.exchange(
            "/admin/flags?endpoint=/api/no-override",
            HttpMethod.POST, withKeyAndBody(FLAG_NO_OVERRIDE_BODY), Void.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    /**
     * Verifies a full config CRUD round-trip: PUT creates a sliding-window config,
     * GET shows it, DELETE removes it, and subsequent GET confirms absence.
     */
    @Test
    void configCrudRoundTrip() {
        final String cfgEndpoint = "/api/roundtrip-config";

        final ResponseEntity<Void> put = restTemplate.exchange(
            "/admin/configs?endpoint=" + cfgEndpoint,
            HttpMethod.POST, withKeyAndBody(CONFIG_BODY), Void.class);
        assertEquals(HttpStatus.OK, put.getStatusCode());

        final ResponseEntity<String> get = restTemplate.exchange(
            "/admin/configs", HttpMethod.GET, withKey(API_KEY), String.class);
        assertTrue(get.getBody().contains(cfgEndpoint),
            "PUT config must appear in subsequent GET");

        restTemplate.exchange("/admin/configs?endpoint=" + cfgEndpoint,
            HttpMethod.DELETE, withKey(API_KEY), Void.class);

        final ResponseEntity<String> afterDelete = restTemplate.exchange(
            "/admin/configs", HttpMethod.GET, withKey(API_KEY), String.class);
        assertFalse(afterDelete.getBody().contains(cfgEndpoint),
            "Deleted config must not appear in subsequent GET");
    }

    /**
     * Verifies the {@code GET /admin/configs} response contains explicit algorithm
     * value fields — {@code limit} and {@code windowMs} for sliding window,
     * confirming Jackson {@code @JsonProperty} serialisation is wired on non-JavaBean methods.
     */
    @Test
    void configsResponseContainsAlgorithmValueFields() {
        final ResponseEntity<String> resp = restTemplate.exchange(
            "/admin/configs", HttpMethod.GET, withKey(API_KEY), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        final String body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"limit\""), "Response must contain limit field");
        assertTrue(body.contains("\"windowMs\""), "Response must contain windowMs field");
    }

    private static final String FLAG_NO_OVERRIDE_BODY =
        "{\"enabled\":true,\"darkLaunch\":false,\"rolloutPercent\":30}";

    private static final String CONFIG_BODY =
        "{\"algorithm\":\"sliding_window\",\"limit\":50,\"windowMs\":30000}";

    private static final String FLAG_BODY =
        "{\"enabled\":true,\"darkLaunch\":false,\"rolloutPercent\":42,"
        + "\"algorithm\":\"token_bucket\",\"capacity\":20,\"refillRatePerSecond\":5}";

    /**
     * Verifies a full flag CRUD round-trip: PUT creates a flag with a token-bucket
     * override, GET shows it with the expected shape fields, DELETE removes it,
     * and subsequent GET confirms absence.
     */
    @Test
    void flagCrudRoundTrip() {
        final String flagEndpoint = "/api/roundtrip-flag";

        final ResponseEntity<Void> put = restTemplate.exchange(
            "/admin/flags?endpoint=" + flagEndpoint,
            HttpMethod.POST, withKeyAndBody(FLAG_BODY), Void.class);
        assertEquals(HttpStatus.OK, put.getStatusCode());

        final ResponseEntity<String> get = restTemplate.exchange(
            "/admin/flags", HttpMethod.GET, withKey(API_KEY), String.class);
        assertEquals(HttpStatus.OK, get.getStatusCode());
        final String flags = get.getBody();
        assertNotNull(flags);
        assertTrue(flags.contains("\"enabled\":true"), "Flag must show enabled=true");
        assertTrue(flags.contains("\"rolloutPercent\":42"), "Flag must show rolloutPercent=42");

        restTemplate.exchange("/admin/flags?endpoint=" + flagEndpoint,
            HttpMethod.DELETE, withKey(API_KEY), Void.class);

        final ResponseEntity<String> afterDelete = restTemplate.exchange(
            "/admin/flags", HttpMethod.GET, withKey(API_KEY), String.class);
        assertFalse(afterDelete.getBody().contains(flagEndpoint),
            "Deleted flag must not appear in subsequent GET");
    }

    /**
     * Verifies {@code GET /admin/audit} returns a JSON array (body starts with {@code [}).
     */
    @Test
    void auditLogBodyIsJsonArray() {
        final ResponseEntity<String> resp = restTemplate.exchange(
            "/admin/audit", HttpMethod.GET, withKey(API_KEY), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        final String body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.startsWith("["), "Audit log response must be a JSON array");
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private HttpEntity<Void> withKey(final String key) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_API_KEY, key);
        return new HttpEntity<>(headers);
    }

    private HttpEntity<String> withKeyAndBody(final String body) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_API_KEY, API_KEY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
