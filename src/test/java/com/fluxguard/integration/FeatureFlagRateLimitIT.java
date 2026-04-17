package com.fluxguard.integration;

import com.fluxguard.config.ConfigService;
import com.fluxguard.config.FeatureFlagService;
import com.fluxguard.config.LimitConfig;
import com.fluxguard.model.FeatureFlag;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Integration tests for feature-flag and kill-switch interactions on real request flow.
 *
 * <p>Uses the benchmark profile to expose `/api/search` so `RateLimitFilter`
 * is exercised end to end against a real Spring context and Redis container.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.profiles.active=benchmark"
)
class FeatureFlagRateLimitIT {

    private static final int REDIS_PORT = 6379;
    private static final String SEARCH_PATH = "/api/search";
    private static final String CLIENT_ID_HEADER = "X-Client-ID";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String CLIENT_IN = "feature-client-in";
    private static final String CLIENT_OUT = "feature-client-out";
    private static final long PRIMARY_LIMIT = 1L;
    private static final long PRIMARY_DARK_LAUNCH_CAPACITY = 2L;
    private static final long PRIMARY_DARK_LAUNCH_REFILL = 10L;
    private static final long OVERRIDE_LIMIT = 3L;
    private static final long OVERRIDE_DARK_LAUNCH_LIMIT = 1L;
    private static final long WINDOW_MS = 60_000L;
    private static final int NO_ROLLOUT = 0;
    private static final int FULL_ROLLOUT = 100;

    @Container
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry reg) {
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ConfigService configService;

    @Autowired
    private FeatureFlagService flagService;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.delete("fluxguard:configs");
        redisTemplate.delete("fluxguard:flags");
        redisTemplate.delete("fluxguard:kill-switch");
        configService.putConfig(SEARCH_PATH,
            LimitConfig.slidingWindow(SEARCH_PATH, PRIMARY_LIMIT, WINDOW_MS));
        flagService.removeFlag(SEARCH_PATH);
        assertFalse(configService.isKillSwitchActive());
    }

    @Test
    void killSwitchAllowsRequestWithoutClientId() {
        configService.setKillSwitch(true);

        final ResponseEntity<String> response =
            restTemplate.getForEntity(SEARCH_PATH, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void liveFlagOutsideRolloutUsesPrimaryLimit() {
        flagService.putFlag(SEARCH_PATH, new FeatureFlag(
            SEARCH_PATH, true, false, NO_ROLLOUT,
            LimitConfig.slidingWindow(SEARCH_PATH, OVERRIDE_LIMIT, WINDOW_MS)));

        assertEquals(HttpStatus.OK, sendSearch(CLIENT_OUT).getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, sendSearch(CLIENT_OUT).getStatusCode());
    }

    @Test
    void liveFlagInsideRolloutUsesOverrideLimit() {
        flagService.putFlag(SEARCH_PATH, new FeatureFlag(
            SEARCH_PATH, true, false, FULL_ROLLOUT,
            LimitConfig.slidingWindow(SEARCH_PATH, OVERRIDE_LIMIT, WINDOW_MS)));

        assertEquals(HttpStatus.OK, sendSearch(CLIENT_IN).getStatusCode());
        assertEquals(HttpStatus.OK, sendSearch(CLIENT_IN).getStatusCode());
        assertEquals(HttpStatus.OK, sendSearch(CLIENT_IN).getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, sendSearch(CLIENT_IN).getStatusCode());
    }

    @Test
    void darkLaunchDoesNotChangeRealDecision() {
        configService.putConfig(SEARCH_PATH,
            LimitConfig.tokenBucket(SEARCH_PATH,
                PRIMARY_DARK_LAUNCH_CAPACITY, PRIMARY_DARK_LAUNCH_REFILL));
        flagService.putFlag(SEARCH_PATH, new FeatureFlag(
            SEARCH_PATH, true, true, FULL_ROLLOUT,
            LimitConfig.slidingWindow(SEARCH_PATH, OVERRIDE_DARK_LAUNCH_LIMIT, WINDOW_MS)));

        final ResponseEntity<String> first = sendSearch(CLIENT_IN);
        final ResponseEntity<String> second = sendSearch(CLIENT_IN);

        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals("1", first.getHeaders().getFirst(REMAINING_HEADER));
        assertEquals(HttpStatus.OK, second.getStatusCode());
    }

    private ResponseEntity<String> sendSearch(final String clientId) {
        final HttpHeaders headers = new HttpHeaders();
        headers.set(CLIENT_ID_HEADER, clientId);
        return restTemplate.exchange(SEARCH_PATH, HttpMethod.GET,
            new HttpEntity<>(headers), String.class);
    }
}
