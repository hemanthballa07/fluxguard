package com.fluxguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxguard.algorithm.SlidingWindowAlgorithm;
import com.fluxguard.algorithm.TokenBucketAlgorithm;
import com.fluxguard.model.FeatureFlag;
import com.fluxguard.util.HashUtil;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisFeatureFlagService}.
 *
 * <p>All Redis interactions are stubbed via Mockito — no Spring context, no Redis.
 */
@SuppressWarnings("unchecked")
class RedisFeatureFlagServiceTest {

    private static final String ENDPOINT_TB   = "/api/ingest";
    private static final String ENDPOINT_SW   = "/api/search";
    private static final long   CAPACITY      = 50L;
    private static final long   REFILL_RATE   = 10L;
    private static final long   LIMIT         = 50L;
    private static final long   WINDOW_MS     = 60_000L;
    private static final int    ROLLOUT_TB    = 25;
    private static final int    ROLLOUT_SW    = 10;
    private static final int    ROLLOUT_ONE   = 1;
    private static final int    ROLLOUT_99    = 99;
    private static final int    SEARCH_SPACE  = 1_000;

    private static final String JSON_TB =
        "{\"enabled\":true,\"darkLaunch\":false,\"rolloutPercent\":25,"
        + "\"algorithm\":\"token_bucket\",\"capacity\":50,\"refillRatePerSecond\":10}";

    private static final String JSON_SW =
        "{\"enabled\":true,\"darkLaunch\":true,\"rolloutPercent\":10,"
        + "\"algorithm\":\"sliding_window\",\"limit\":50,\"windowMs\":60000}";

    private static final String JSON_SW_PUT =
        "{\"enabled\":true,\"darkLaunch\":false,\"rolloutPercent\":10,"
        + "\"algorithm\":\"sliding_window\",\"limit\":50,\"windowMs\":60000}";

    private static final String JSON_NO_OVERRIDE =
        "{\"enabled\":true,\"darkLaunch\":false,\"rolloutPercent\":10}";

    private HashOperations<String, Object, Object> hashOps;
    private StringRedisTemplate redis;
    private RedisFeatureFlagService service;

    @BeforeEach
    void setUp() {
        hashOps = mock(HashOperations.class);
        redis   = mock(StringRedisTemplate.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        service = new RedisFeatureFlagService(redis, new ObjectMapper());
    }

    @Test
    void getFlagTokenBucketRoundTrip() {
        when(hashOps.get(RedisFeatureFlagService.FLAGS_HASH, ENDPOINT_TB)).thenReturn(JSON_TB);

        final Optional<FeatureFlag> result = service.getFlagForEndpoint(ENDPOINT_TB);

        assertTrue(result.isPresent());
        final FeatureFlag flag = result.get();
        assertTrue(flag.enabled());
        assertFalse(flag.darkLaunch());
        assertEquals(ROLLOUT_TB, flag.rolloutPercent());
        assertTrue(flag.overrideConfig().algorithm() instanceof TokenBucketAlgorithm);
        final TokenBucketAlgorithm tb = (TokenBucketAlgorithm) flag.overrideConfig().algorithm();
        assertEquals(CAPACITY, tb.capacity());
        assertEquals(REFILL_RATE, tb.refillRatePerSecond());
    }

    @Test
    void getFlagSlidingWindowRoundTrip() {
        when(hashOps.get(RedisFeatureFlagService.FLAGS_HASH, ENDPOINT_SW)).thenReturn(JSON_SW);

        final Optional<FeatureFlag> result = service.getFlagForEndpoint(ENDPOINT_SW);

        assertTrue(result.isPresent());
        final FeatureFlag flag = result.get();
        assertTrue(flag.enabled());
        assertTrue(flag.darkLaunch());
        assertEquals(ROLLOUT_SW, flag.rolloutPercent());
        assertTrue(flag.overrideConfig().algorithm() instanceof SlidingWindowAlgorithm);
        final SlidingWindowAlgorithm sw = (SlidingWindowAlgorithm) flag.overrideConfig().algorithm();
        assertEquals(LIMIT, sw.limit());
        assertEquals(WINDOW_MS, sw.windowMs());
    }

    @Test
    void getFlagAbsentReturnsEmpty() {
        when(hashOps.get(RedisFeatureFlagService.FLAGS_HASH, ENDPOINT_TB)).thenReturn(null);

        assertTrue(service.getFlagForEndpoint(ENDPOINT_TB).isEmpty());
    }

    @Test
    void getFlagMalformedJsonReturnsEmpty() {
        when(hashOps.get(RedisFeatureFlagService.FLAGS_HASH, ENDPOINT_TB)).thenReturn("not-json");

        assertTrue(service.getFlagForEndpoint(ENDPOINT_TB).isEmpty());
    }

    @Test
    void getFlagUnknownAlgorithmReturnsEmpty() {
        when(hashOps.get(RedisFeatureFlagService.FLAGS_HASH, ENDPOINT_TB))
            .thenReturn("{\"enabled\":true,\"darkLaunch\":false,\"rolloutPercent\":10,"
                + "\"algorithm\":\"unknown\",\"x\":1}");

        assertTrue(service.getFlagForEndpoint(ENDPOINT_TB).isEmpty());
    }

    @Test
    void getAllFlagsSkipsMalformedEntries() {
        when(hashOps.entries(RedisFeatureFlagService.FLAGS_HASH)).thenReturn(Map.of(
            ENDPOINT_TB, JSON_TB,
            ENDPOINT_SW, "bad-json"
        ));

        final Map<String, FeatureFlag> result = service.getAllFlags();

        assertEquals(1, result.size());
        assertTrue(result.containsKey(ENDPOINT_TB));
    }

    @Test
    void putFlagSerializesTokenBucket() {
        final LimitConfig override = LimitConfig.tokenBucket(ENDPOINT_TB, CAPACITY, REFILL_RATE);
        final FeatureFlag flag = new FeatureFlag(ENDPOINT_TB, true, false, ROLLOUT_TB, override);

        service.putFlag(ENDPOINT_TB, flag);

        verify(hashOps).put(eq(RedisFeatureFlagService.FLAGS_HASH), eq(ENDPOINT_TB), eq(JSON_TB));
    }

    @Test
    void putFlagSerializesSlidingWindow() {
        final LimitConfig override = LimitConfig.slidingWindow(ENDPOINT_SW, LIMIT, WINDOW_MS);
        final FeatureFlag flag = new FeatureFlag(ENDPOINT_SW, true, false, ROLLOUT_SW, override);

        service.putFlag(ENDPOINT_SW, flag);

        verify(hashOps).put(eq(RedisFeatureFlagService.FLAGS_HASH), eq(ENDPOINT_SW), eq(JSON_SW_PUT));
    }

    @Test
    void removeFlagDeletesHashField() {
        service.removeFlag(ENDPOINT_TB);

        verify(hashOps).delete(RedisFeatureFlagService.FLAGS_HASH, ENDPOINT_TB);
    }

    @Test
    void isClientInRolloutFalseWhenZeroPercent() {
        final LimitConfig override = LimitConfig.slidingWindow(ENDPOINT_SW, LIMIT, WINDOW_MS);
        final FeatureFlag flag = new FeatureFlag(ENDPOINT_SW, true, false, 0, override);

        assertFalse(service.isClientInRollout(flag, "any-client"));
    }

    @Test
    void isClientInRolloutTrueWhenHundredPercent() {
        final LimitConfig override = LimitConfig.slidingWindow(ENDPOINT_SW, LIMIT, WINDOW_MS);
        final FeatureFlag flag = new FeatureFlag(ENDPOINT_SW, true, false, 100, override);

        assertTrue(service.isClientInRollout(flag, "any-client"));
    }

    @Test
    void isClientInRolloutStableForSameClient() {
        final LimitConfig override = LimitConfig.slidingWindow(ENDPOINT_SW, LIMIT, WINDOW_MS);
        final FeatureFlag flag = new FeatureFlag(ENDPOINT_SW, true, false, ROLLOUT_TB, override);
        final String clientId = "stable-client";

        final boolean first = service.isClientInRollout(flag, clientId);
        final boolean second = service.isClientInRollout(flag, clientId);

        assertEquals(first, second);
    }

    @Test
    void isClientInRolloutOnePercentIncludesBucketZeroClient() {
        final LimitConfig override = LimitConfig.slidingWindow(ENDPOINT_SW, LIMIT, WINDOW_MS);
        final FeatureFlag flag = new FeatureFlag(ENDPOINT_SW, true, false, ROLLOUT_ONE, override);

        assertTrue(service.isClientInRollout(flag, findClientWithBucket(0)));
    }

    @Test
    void isClientInRolloutNinetyNinePercentExcludesLastBucketClient() {
        final LimitConfig override = LimitConfig.slidingWindow(ENDPOINT_SW, LIMIT, WINDOW_MS);
        final FeatureFlag flag = new FeatureFlag(ENDPOINT_SW, true, false, ROLLOUT_99, override);

        assertFalse(service.isClientInRollout(flag, findClientWithBucket(HashUtil.BUCKET_COUNT - 1)));
    }

    @Test
    void putFlagWithNullOverrideSerializesWithoutAlgorithmFields() {
        final FeatureFlag flag = new FeatureFlag(ENDPOINT_SW, true, false, ROLLOUT_SW, null);

        service.putFlag(ENDPOINT_SW, flag);

        verify(hashOps).put(
            eq(RedisFeatureFlagService.FLAGS_HASH), eq(ENDPOINT_SW), eq(JSON_NO_OVERRIDE));
    }

    @Test
    void getFlagWithNullOverrideDeserializesCorrectly() {
        when(hashOps.get(RedisFeatureFlagService.FLAGS_HASH, ENDPOINT_SW))
            .thenReturn(JSON_NO_OVERRIDE);

        final Optional<FeatureFlag> result = service.getFlagForEndpoint(ENDPOINT_SW);

        assertTrue(result.isPresent(), "Flag without override must deserialize to present");
        final FeatureFlag flag = result.get();
        assertTrue(flag.enabled());
        assertFalse(flag.darkLaunch());
        assertEquals(ROLLOUT_SW, flag.rolloutPercent());
        assertNull(flag.overrideConfig(), "Flag without override must have null overrideConfig");
    }

    private String findClientWithBucket(final int bucket) {
        for (int i = 0; i < SEARCH_SPACE; i++) {
            final String clientId = "client-" + i;
            if (HashUtil.rolloutBucket(ENDPOINT_SW, clientId) == bucket) {
                return clientId;
            }
        }
        throw new AssertionError("No client found for bucket " + bucket);
    }
}
