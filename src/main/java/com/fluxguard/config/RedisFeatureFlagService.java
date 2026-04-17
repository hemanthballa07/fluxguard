package com.fluxguard.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fluxguard.algorithm.SlidingWindowAlgorithm;
import com.fluxguard.algorithm.TokenBucketAlgorithm;
import com.fluxguard.model.FeatureFlag;
import com.fluxguard.util.HashUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link FeatureFlagService} implementation backed by a Redis hash.
 *
 * <p>Flags are stored as flat JSON in the hash at key {@code fluxguard:flags}.
 * Each hash field is an endpoint path; each value is a JSON object with the flag's
 * settings and algorithm override flattened into a single level.
 *
 * <p>This class is registered exclusively via {@code @Bean} in
 * {@link RateLimitConfiguration} — it carries no {@code @Component} annotation.
 *
 * <p>See ADR-005 for the storage strategy and rollout algorithm.
 */
public class RedisFeatureFlagService implements FeatureFlagService {

    private static final Logger LOG = LoggerFactory.getLogger(RedisFeatureFlagService.class);

    /** Redis hash key that stores all feature flags. */
    public static final String FLAGS_HASH = "fluxguard:flags";

    private static final String FIELD_ENABLED       = "enabled";
    private static final String FIELD_DARK_LAUNCH   = "darkLaunch";
    private static final String FIELD_ROLLOUT       = "rolloutPercent";
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
    public RedisFeatureFlagService(
            final StringRedisTemplate redis,
            final ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@link Optional#empty()} if the endpoint is absent, its JSON is malformed,
     * or its algorithm field is unrecognised. Errors are logged at WARN level.
     */
    @Override
    public Optional<FeatureFlag> getFlagForEndpoint(final String endpoint) {
        final Object raw = redis.opsForHash().get(FLAGS_HASH, endpoint);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return deserialize(endpoint, raw.toString());
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed flag for endpoint={} — {}", endpoint, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>A client is in rollout when its deterministic bucket index is strictly less than
     * {@code flag.rolloutPercent()}. Rollout 0 always returns {@code false};
     * rollout 100 always returns {@code true}.
     */
    @Override
    public boolean isClientInRollout(final FeatureFlag flag, final String clientId) {
        return HashUtil.rolloutBucket(flag.endpoint(), clientId) < flag.rolloutPercent();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Malformed entries are excluded and logged at WARN level.
     */
    @Override
    public Map<String, FeatureFlag> getAllFlags() {
        final Map<Object, Object> entries = redis.opsForHash().entries(FLAGS_HASH);
        final Map<String, FeatureFlag> result = new HashMap<>();
        for (final Map.Entry<Object, Object> entry : entries.entrySet()) {
            final String endpoint = entry.getKey().toString();
            try {
                deserialize(endpoint, entry.getValue().toString())
                    .ifPresent(flag -> result.put(endpoint, flag));
            } catch (JsonProcessingException | IllegalArgumentException ex) {
                LOG.warn("Skipping malformed flag for endpoint={} — {}", endpoint, ex.getMessage());
            }
        }
        return Map.copyOf(result);
    }

    /** {@inheritDoc} */
    @Override
    public void putFlag(final String endpoint, final FeatureFlag flag) {
        redis.opsForHash().put(FLAGS_HASH, endpoint, serialize(flag));
    }

    /** {@inheritDoc} */
    @Override
    public void removeFlag(final String endpoint) {
        redis.opsForHash().delete(FLAGS_HASH, endpoint);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private String serialize(final FeatureFlag flag) {
        final ObjectNode node = objectMapper.createObjectNode();
        node.put(FIELD_ENABLED, flag.enabled());
        node.put(FIELD_DARK_LAUNCH, flag.darkLaunch());
        node.put(FIELD_ROLLOUT, flag.rolloutPercent());
        if (flag.overrideConfig().algorithm() instanceof TokenBucketAlgorithm tb) {
            node.put(FIELD_ALGORITHM, ALGO_TOKEN_BUCKET);
            node.put(FIELD_CAPACITY, tb.capacity());
            node.put(FIELD_REFILL_RATE, tb.refillRatePerSecond());
        } else if (flag.overrideConfig().algorithm() instanceof SlidingWindowAlgorithm sw) {
            node.put(FIELD_ALGORITHM, ALGO_SLIDING_WINDOW);
            node.put(FIELD_LIMIT, sw.limit());
            node.put(FIELD_WINDOW_MS, sw.windowMs());
        } else {
            throw new IllegalArgumentException(
                "Unsupported algorithm: "
                    + flag.overrideConfig().algorithm().getClass().getSimpleName());
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize feature flag", ex);
        }
    }

    private Optional<FeatureFlag> deserialize(final String endpoint, final String json)
            throws JsonProcessingException {
        final JsonNode node      = objectMapper.readTree(json);
        final boolean enabled    = node.path(FIELD_ENABLED).asBoolean();
        final boolean darkLaunch = node.path(FIELD_DARK_LAUNCH).asBoolean();
        final int rollout        = node.path(FIELD_ROLLOUT).asInt();
        final String algorithm   = node.path(FIELD_ALGORITHM).asText();
        final LimitConfig overrideConfig;
        if (ALGO_TOKEN_BUCKET.equals(algorithm)) {
            if (!node.hasNonNull(FIELD_CAPACITY) || !node.hasNonNull(FIELD_REFILL_RATE)) {
                return Optional.empty();
            }
            overrideConfig = LimitConfig.tokenBucket(
                endpoint,
                node.get(FIELD_CAPACITY).asLong(),
                node.get(FIELD_REFILL_RATE).asLong());
        } else if (ALGO_SLIDING_WINDOW.equals(algorithm)) {
            if (!node.hasNonNull(FIELD_LIMIT) || !node.hasNonNull(FIELD_WINDOW_MS)) {
                return Optional.empty();
            }
            overrideConfig = LimitConfig.slidingWindow(
                endpoint,
                node.get(FIELD_LIMIT).asLong(),
                node.get(FIELD_WINDOW_MS).asLong());
        } else {
            return Optional.empty();
        }
        return Optional.of(
            new FeatureFlag(endpoint, enabled, darkLaunch, rollout, overrideConfig));
    }
}
