package com.fluxguard.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fluxguard.algorithm.SlidingWindowAlgorithm;
import com.fluxguard.algorithm.TokenBucketAlgorithm;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link ConfigService} implementation backed by a Redis hash.
 *
 * <p>Configs are stored as flat JSON in the hash at key {@code fluxguard:configs}.
 * The kill switch is a separate plain key {@code fluxguard:kill-switch}.
 *
 * <p>This class is registered exclusively via {@code @Bean} in
 * {@link RateLimitConfiguration} — it carries no {@code @Component} annotation.
 *
 * <p>See ADR-004 for the storage strategy and the amended Redis-caller rule.
 */
public class RedisConfigService implements ConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(RedisConfigService.class);

    static final String CONFIG_HASH  = "fluxguard:configs";
    static final String KILL_SWITCH  = "fluxguard:kill-switch";
    static final String ACTIVE_VALUE = "1";

    private static final String FIELD_ALGORITHM     = "algorithm";
    private static final String FIELD_CAPACITY      = "capacity";
    private static final String FIELD_REFILL_RATE   = "refillRatePerSecond";
    private static final String FIELD_LIMIT         = "limit";
    private static final String FIELD_WINDOW_MS     = "windowMs";
    private static final String ALGO_TOKEN_BUCKET   = "token_bucket";
    private static final String ALGO_SLIDING_WINDOW = "sliding_window";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the service with its Redis template and JSON mapper.
     *
     * @param redis        Spring Data Redis string template
     * @param objectMapper Jackson mapper for JSON serialisation
     */
    public RedisConfigService(
            final StringRedisTemplate redis,
            final ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@link Optional#empty()} if the endpoint is absent or its stored
     * JSON is malformed; the error is logged at WARN level.
     */
    @Override
    public Optional<LimitConfig> getConfig(final String endpoint) {
        final Object raw = redis.opsForHash().get(CONFIG_HASH, endpoint);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(deserialize(endpoint, raw.toString()));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed config entry for endpoint={} — {}", endpoint, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Malformed entries are excluded from the result and logged at WARN level.
     */
    @Override
    public Map<String, LimitConfig> getAllConfigs() {
        final Map<Object, Object> entries = redis.opsForHash().entries(CONFIG_HASH);
        final Map<String, LimitConfig> result = new HashMap<>();
        for (final Map.Entry<Object, Object> entry : entries.entrySet()) {
            final String endpoint = entry.getKey().toString();
            try {
                result.put(endpoint, deserialize(endpoint, entry.getValue().toString()));
            } catch (JsonProcessingException | IllegalArgumentException ex) {
                LOG.warn("Skipping malformed config entry for endpoint={} — {}", endpoint, ex.getMessage());
            }
        }
        return Map.copyOf(result);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putConfig(final String endpoint, final LimitConfig config) {
        redis.opsForHash().put(CONFIG_HASH, endpoint, serialize(config));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeConfig(final String endpoint) {
        redis.opsForHash().delete(CONFIG_HASH, endpoint);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isKillSwitchActive() {
        return ACTIVE_VALUE.equals(redis.opsForValue().get(KILL_SWITCH));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setKillSwitch(final boolean active) {
        if (active) {
            redis.opsForValue().set(KILL_SWITCH, ACTIVE_VALUE);
        } else {
            redis.delete(KILL_SWITCH);
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private String serialize(final LimitConfig config) {
        final ObjectNode node = objectMapper.createObjectNode();
        if (config.algorithm() instanceof TokenBucketAlgorithm tb) {
            node.put(FIELD_ALGORITHM, ALGO_TOKEN_BUCKET);
            node.put(FIELD_CAPACITY, tb.capacity());
            node.put(FIELD_REFILL_RATE, tb.refillRatePerSecond());
        } else if (config.algorithm() instanceof SlidingWindowAlgorithm sw) {
            node.put(FIELD_ALGORITHM, ALGO_SLIDING_WINDOW);
            node.put(FIELD_LIMIT, sw.limit());
            node.put(FIELD_WINDOW_MS, sw.windowMs());
        } else {
            throw new IllegalArgumentException(
                "Unsupported algorithm: " + config.algorithm().getClass().getSimpleName());
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize config", ex);
        }
    }

    private LimitConfig deserialize(final String endpoint, final String json)
            throws JsonProcessingException {
        final JsonNode node = objectMapper.readTree(json);
        final String algorithm = node.get(FIELD_ALGORITHM).asText();
        return switch (algorithm) {
            case ALGO_TOKEN_BUCKET -> LimitConfig.tokenBucket(
                endpoint,
                node.get(FIELD_CAPACITY).asLong(),
                node.get(FIELD_REFILL_RATE).asLong());
            case ALGO_SLIDING_WINDOW -> LimitConfig.slidingWindow(
                endpoint,
                node.get(FIELD_LIMIT).asLong(),
                node.get(FIELD_WINDOW_MS).asLong());
            default -> throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
        };
    }
}
