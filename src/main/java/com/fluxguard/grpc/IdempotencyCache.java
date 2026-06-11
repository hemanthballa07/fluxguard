package com.fluxguard.grpc;

import com.fluxguard.model.RateLimitDecision;
import com.fluxguard.redis.LuaScriptExecutor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Idempotency cache for gRPC rate-limit decisions, backed by two atomic Lua scripts.
 *
 * <p>The {@code idem_reserve_or_get} script either reserves a slot for a first-seen
 * key, reports a concurrent in-flight reservation, or returns a previously stored
 * decision. The {@code idem_store} script writes the final encoded decision so a
 * retried request with the same idempotency key replays the original outcome.
 *
 * <p>All Redis failures fail open: {@link #lookup(String)} returns {@link Bypass}
 * (so the caller runs the engine without caching) and {@link #store} swallows the
 * error. Rate limiting must never break the calling service.
 */
@Component
public class IdempotencyCache {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyCache.class);

    /** Lua script that reserves a slot or returns the cached decision. */
    static final String SCRIPT_RESERVE_OR_GET = "idem_reserve_or_get";

    /** Lua script that stores the final decision under the idempotency key. */
    static final String SCRIPT_STORE = "idem_store";

    /** TTL (ms) for the transient {@code pending} reservation written on first sight. */
    static final long RESERVE_TTL_MS = 5_000L;

    /** TTL (ms) applied to a cached allow decision. */
    static final long ALLOW_TTL_MS = 300_000L;

    /** Reserve-or-get reply code: caller is the first to see this key. */
    private static final long CODE_FIRST = 1L;

    /** Reserve-or-get reply code: a concurrent caller holds the reservation. */
    private static final long CODE_CONCURRENT = 2L;

    /** Number of components in an encoded decision string. */
    private static final int DECISION_PARTS = 3;

    /** Index of the allowed flag in a decoded decision string. */
    private static final int PART_ALLOWED = 0;

    /** Index of the remaining-tokens value in a decoded decision string. */
    private static final int PART_REMAINING = 1;

    /** Index of the reset-after-ms value in a decoded decision string. */
    private static final int PART_RESET = 2;

    private final LuaScriptExecutor executor;

    /**
     * Constructs the cache with the Lua script executor.
     *
     * @param executor executes the idempotency Lua scripts against Redis
     */
    public IdempotencyCache(final LuaScriptExecutor executor) {
        this.executor = executor;
    }

    /**
     * Reserves or replays the decision for the given idempotency Redis key.
     *
     * <p>Returns {@link First} when this caller must run the engine and store the
     * result, {@link Concurrent} when another caller is in flight, {@link Hit} with
     * the replayed decision when one is cached, or {@link Bypass} when Redis is
     * unavailable (fail-open).
     *
     * @param redisKey fully-qualified idempotency key
     * @return the lookup outcome
     */
    public IdemLookup lookup(final String redisKey) {
        try {
            final List<Object> reply = executor.execute(
                SCRIPT_RESERVE_OR_GET, List.of(redisKey), List.of(String.valueOf(RESERVE_TTL_MS)));
            final long code = (Long) reply.get(0);
            if (code == CODE_FIRST) {
                return new First();
            }
            if (code == CODE_CONCURRENT) {
                return new Concurrent();
            }
            return new Hit(decode((String) reply.get(1)));
        } catch (RuntimeException ex) {
            LOG.warn("Idempotency lookup failed for key={} — bypassing cache: {}",
                redisKey, ex.getMessage());
            return new Bypass();
        }
    }

    /**
     * Stores the final decision for the given idempotency Redis key.
     *
     * <p>Allowed decisions are cached for {@link #ALLOW_TTL_MS}; denied decisions
     * are cached only until the client may retry. Redis failures are swallowed so
     * the calling request still completes.
     *
     * @param redisKey fully-qualified idempotency key
     * @param decision the decision to cache
     */
    public void store(final String redisKey, final RateLimitDecision decision) {
        final long ttlMs = decision.allowed() ? ALLOW_TTL_MS : Math.max(1L, decision.resetAfterMs());
        final String decisionStr = (decision.allowed() ? "1" : "0")
            + ":" + decision.remainingTokens()
            + ":" + decision.resetAfterMs();
        try {
            executor.execute(SCRIPT_STORE, List.of(redisKey),
                List.of(decisionStr, String.valueOf(ttlMs)));
        } catch (RuntimeException ex) {
            LOG.warn("Idempotency store failed for key={} — decision not cached: {}",
                redisKey, ex.getMessage());
        }
    }

    private static RateLimitDecision decode(final String encoded) {
        final String[] parts = encoded.split(":", DECISION_PARTS);
        final boolean allowed = "1".equals(parts[PART_ALLOWED]);
        if (allowed) {
            return RateLimitDecision.allow(Long.parseLong(parts[PART_REMAINING]));
        }
        return RateLimitDecision.deny(Long.parseLong(parts[PART_RESET]));
    }

    /**
     * Outcome of an idempotency lookup.
     */
    public sealed interface IdemLookup permits First, Concurrent, Hit, Bypass {
    }

    /**
     * The caller is the first to see this key and must run the engine then store.
     */
    public record First() implements IdemLookup {
    }

    /**
     * Another caller holds the reservation; this request should be allowed through.
     */
    public record Concurrent() implements IdemLookup {
    }

    /**
     * A previously stored decision was found and should be replayed.
     *
     * @param decision the cached decision
     */
    public record Hit(RateLimitDecision decision) implements IdemLookup {
    }

    /**
     * Redis was unavailable; the caller should run the engine without caching.
     */
    public record Bypass() implements IdemLookup {
    }
}
