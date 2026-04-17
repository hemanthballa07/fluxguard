package com.fluxguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxguard.algorithm.SlidingWindowAlgorithm;
import com.fluxguard.algorithm.TokenBucketAlgorithm;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisConfigService}.
 *
 * <p>All Redis interactions are stubbed via Mockito — no Spring context, no Redis.
 */
@SuppressWarnings("unchecked")
class RedisConfigServiceTest {

    private static final String ENDPOINT_TB = "/api/ingest";
    private static final String ENDPOINT_SW = "/api/search";
    private static final long   CAPACITY    = 50L;
    private static final long   REFILL_RATE = 10L;
    private static final long   LIMIT       = 100L;
    private static final long   WINDOW_MS   = 60_000L;

    private static final String JSON_TB =
        "{\"algorithm\":\"token_bucket\",\"capacity\":50,\"refillRatePerSecond\":10}";
    private static final String JSON_SW =
        "{\"algorithm\":\"sliding_window\",\"limit\":100,\"windowMs\":60000}";

    private HashOperations<String, Object, Object> hashOps;
    private ValueOperations<String, String> valueOps;
    private StringRedisTemplate redis;
    private RedisConfigService service;

    @BeforeEach
    void setUp() {
        hashOps  = mock(HashOperations.class);
        valueOps = mock(ValueOperations.class);
        redis    = mock(StringRedisTemplate.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        service  = new RedisConfigService(redis, new ObjectMapper());
    }

    @Test
    void getConfigTokenBucketRoundTrip() {
        when(hashOps.get(RedisConfigService.CONFIG_HASH, ENDPOINT_TB)).thenReturn(JSON_TB);

        final Optional<LimitConfig> result = service.getConfig(ENDPOINT_TB);

        assertTrue(result.isPresent());
        assertTrue(result.get().algorithm() instanceof TokenBucketAlgorithm);
        final TokenBucketAlgorithm tb = (TokenBucketAlgorithm) result.get().algorithm();
        assertEquals(CAPACITY, tb.capacity());
        assertEquals(REFILL_RATE, tb.refillRatePerSecond());
    }

    @Test
    void getConfigSlidingWindowRoundTrip() {
        when(hashOps.get(RedisConfigService.CONFIG_HASH, ENDPOINT_SW)).thenReturn(JSON_SW);

        final Optional<LimitConfig> result = service.getConfig(ENDPOINT_SW);

        assertTrue(result.isPresent());
        assertTrue(result.get().algorithm() instanceof SlidingWindowAlgorithm);
        final SlidingWindowAlgorithm sw = (SlidingWindowAlgorithm) result.get().algorithm();
        assertEquals(LIMIT, sw.limit());
        assertEquals(WINDOW_MS, sw.windowMs());
    }

    @Test
    void getConfigAbsentReturnsEmpty() {
        when(hashOps.get(RedisConfigService.CONFIG_HASH, ENDPOINT_TB)).thenReturn(null);

        assertTrue(service.getConfig(ENDPOINT_TB).isEmpty());
    }

    @Test
    void getConfigMalformedJsonReturnsEmpty() {
        when(hashOps.get(RedisConfigService.CONFIG_HASH, ENDPOINT_TB)).thenReturn("not-json");

        assertTrue(service.getConfig(ENDPOINT_TB).isEmpty());
    }

    @Test
    void getConfigUnknownAlgorithmReturnsEmpty() {
        when(hashOps.get(RedisConfigService.CONFIG_HASH, ENDPOINT_TB))
            .thenReturn("{\"algorithm\":\"unknown\",\"x\":1}");

        assertTrue(service.getConfig(ENDPOINT_TB).isEmpty());
    }

    @Test
    void getAllConfigsSkipsMalformedEntries() {
        when(hashOps.entries(RedisConfigService.CONFIG_HASH)).thenReturn(Map.of(
            ENDPOINT_TB, JSON_TB,
            ENDPOINT_SW, "bad-json"
        ));

        final Map<String, LimitConfig> result = service.getAllConfigs();

        assertEquals(1, result.size());
        assertTrue(result.containsKey(ENDPOINT_TB));
    }

    @Test
    void putConfigSerializesTokenBucket() {
        final LimitConfig config = LimitConfig.tokenBucket(ENDPOINT_TB, CAPACITY, REFILL_RATE);

        service.putConfig(ENDPOINT_TB, config);

        verify(hashOps).put(eq(RedisConfigService.CONFIG_HASH), eq(ENDPOINT_TB), eq(JSON_TB));
    }

    @Test
    void removeConfigDeletesHashField() {
        service.removeConfig(ENDPOINT_TB);

        verify(hashOps).delete(RedisConfigService.CONFIG_HASH, ENDPOINT_TB);
    }

    @Test
    void isKillSwitchActiveReturnsTrueWhenSet() {
        when(valueOps.get(RedisConfigService.KILL_SWITCH))
            .thenReturn(RedisConfigService.ACTIVE_VALUE);

        assertTrue(service.isKillSwitchActive());
    }

    @Test
    void isKillSwitchActiveReturnsFalseWhenAbsent() {
        when(valueOps.get(RedisConfigService.KILL_SWITCH)).thenReturn(null);

        assertFalse(service.isKillSwitchActive());
    }

    @Test
    void setKillSwitchTrueSetsKey() {
        service.setKillSwitch(true);

        verify(valueOps).set(RedisConfigService.KILL_SWITCH, RedisConfigService.ACTIVE_VALUE);
    }

    @Test
    void setKillSwitchFalseDeletesKey() {
        service.setKillSwitch(false);

        verify(redis).delete(RedisConfigService.KILL_SWITCH);
    }
}
