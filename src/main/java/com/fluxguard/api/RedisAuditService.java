package com.fluxguard.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link AuditService} implementation backed by a Redis list.
 *
 * <p>Every call to {@link #record} performs a dual-write:
 * <ol>
 *   <li>An INFO log entry via the {@code audit.admin} logger (always fires, even on Redis failure).</li>
 *   <li>A {@code RPUSH} to the Redis list {@code fluxguard:audit-log}, capped at
 *       {@value #MAX_LOG_SIZE} entries via {@code LTRIM}.</li>
 * </ol>
 *
 * <p>Redis writes are fail-open: any {@link RuntimeException} is caught, logged at WARN,
 * and swallowed so that the admin operation completes regardless.
 *
 * <p>This class carries no {@code @Component} annotation; it is registered
 * exclusively via {@code @Bean} in {@code RateLimitConfiguration}.
 */
public class RedisAuditService implements AuditService {

    private static final Logger LOG       = LoggerFactory.getLogger(RedisAuditService.class);
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("audit.admin");

    /** Redis list key for the queryable audit log. */
    public static final String AUDIT_LOG_KEY = "fluxguard:audit-log";

    private static final long   MAX_LOG_SIZE  = 10_000L;
    private static final String ACTOR_UNKNOWN = "unknown";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the service with its Redis template and JSON mapper.
     *
     * @param redis        Spring Data Redis string template
     * @param objectMapper Jackson mapper for JSON serialisation
     */
    public RedisAuditService(
            final StringRedisTemplate redis,
            final ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A null or blank {@code actor} is normalised to {@code "unknown"}.
     * The structured INFO log entry fires before the Redis write and is
     * unaffected by Redis failures.
     */
    @Override
    public void record(
            final String action,
            final String target,
            final String details,
            final String actor) {
        final String resolvedActor = (actor == null || actor.isBlank()) ? ACTOR_UNKNOWN : actor;
        final String json = buildJson(action, target, details, resolvedActor);
        AUDIT_LOG.info(json);
        try {
            redis.opsForList().rightPush(AUDIT_LOG_KEY, json);
            redis.opsForList().trim(AUDIT_LOG_KEY, -MAX_LOG_SIZE, -1L);
        } catch (RuntimeException ex) {
            LOG.warn("Audit Redis write failed for action={} — {}", action, ex.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses Redis {@code LRANGE} with negative indices: {@code range(-count, -1)}
     * returns the last {@code count} elements with the oldest first, newest last.
     */
    @Override
    public List<String> getRecent(final int count) {
        try {
            final List<String> result = redis.opsForList().range(AUDIT_LOG_KEY, -count, -1L);
            return result != null ? result : Collections.emptyList();
        } catch (RuntimeException ex) {
            LOG.warn("Audit Redis read failed — {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private String buildJson(
            final String action,
            final String target,
            final String details,
            final String actor) {
        final ObjectNode node = objectMapper.createObjectNode();
        node.put("timestamp", System.currentTimeMillis());
        node.put("action", action);
        node.put("target", target);
        node.put("details", details);
        node.put("actor", actor);
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize audit entry", ex);
        }
    }
}
