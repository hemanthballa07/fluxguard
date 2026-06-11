package com.fluxguard.grpc;

import com.fluxguard.config.LimitConfig;
import com.fluxguard.model.ClientIdentity;
import com.fluxguard.model.RateLimitDecision;
import com.fluxguard.redis.LuaScriptExecutor;
import com.fluxguard.util.ClockProvider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outcome-aware sliding-window throttle for the {@code LOGIN} policy.
 *
 * <p>Login throttling is split into two phases so that successful logins never
 * consume window budget:
 * <ul>
 *   <li>{@link #check} runs the READ-ONLY {@code sliding_window_peek} script to
 *       decide whether the client is currently under its failure limit. It never
 *       writes to Redis, so a {@code CheckLimit(LOGIN)} that allows a sign-in
 *       attempt does not itself count against the window.</li>
 *   <li>{@link #recordFailure} runs the INCREMENT-ONLY {@code sliding_window_incr}
 *       script, called only after a login attempt actually fails, to advance the
 *       counter that {@link #check} reads.</li>
 * </ul>
 *
 * <p>Both methods are <em>fail-open / best-effort</em>: a Redis outage must never
 * block a legitimate sign-in nor surface as an RPC error.
 */
@Component
public class LoginThrottle {

    private static final Logger LOG = LoggerFactory.getLogger(LoginThrottle.class);

    /** Read-only peek script: estimates window usage without incrementing. */
    static final String SCRIPT_PEEK = "sliding_window_peek";

    /** Increment-only script: bumps the current-window counter on a failure. */
    static final String SCRIPT_INCR = "sliding_window_incr";

    /** Remaining count reported on a fail-open allow when Redis is unavailable. */
    private static final long FAIL_OPEN_REMAINING = 0L;

    /** Index of the new counter value in the {@code sliding_window_incr} reply. */
    private static final int IDX_INCR_COUNT = 0;

    /** Sentinel returned by {@link #recordFailure} when the increment fails. */
    private static final long RECORD_FAILED = -1L;

    private final LuaScriptExecutor executor;
    private final ClockProvider clock;

    /**
     * Constructs the throttle with its Redis executor and clock.
     *
     * @param executor runs the peek/increment Lua scripts against Redis
     * @param clock    supplies the current epoch time for window indexing
     */
    public LoginThrottle(final LuaScriptExecutor executor, final ClockProvider clock) {
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Peeks at the sliding window for {@code clientIp} without consuming a slot.
     *
     * <p>On any Redis failure this fails open, returning an allow decision flagged
     * with {@code failOpen = true} so a login is never blocked by an outage.
     *
     * @param clientIp the client IP being throttled (the rate-limit subject)
     * @param config   the resolved LOGIN limit configuration
     * @return the peek decision and whether it was produced by failing open
     */
    public LoginCheck check(final String clientIp, final LimitConfig config) {
        final long now = clock.nowMillis();
        final String key = ClientIdentity.of(clientIp, config.endpointPattern()).bucketKey();
        final List<String> keys = config.algorithm().buildLuaKeys(key, now);
        final List<String> args = config.algorithm().buildLuaArgs(now);
        try {
            final List<Object> result = executor.execute(SCRIPT_PEEK, keys, args);
            final RateLimitDecision decision = config.algorithm().parseResult(result);
            return new LoginCheck(decision, false);
        } catch (RuntimeException ex) {
            LOG.warn("LOGIN peek failed for client_ip={} — failing open: {}",
                clientIp, ex.getMessage());
            return new LoginCheck(RateLimitDecision.allow(FAIL_OPEN_REMAINING), true);
        }
    }

    /**
     * Records a failed login by incrementing the current-window counter.
     *
     * <p>Best-effort: on any Redis failure this swallows the error and returns
     * {@code -1} rather than propagating, so reporting a failure can never break
     * the calling RPC.
     *
     * @param clientIp the client IP whose failure is being recorded
     * @param config   the resolved LOGIN limit configuration
     * @return the new failure count in the window, or {@code -1} when the write failed
     */
    public long recordFailure(final String clientIp, final LimitConfig config) {
        final long now = clock.nowMillis();
        final String key = ClientIdentity.of(clientIp, config.endpointPattern()).bucketKey();
        final List<String> keys = config.algorithm().buildLuaKeys(key, now);
        final List<String> args = config.algorithm().buildLuaArgs(now);
        try {
            final List<Object> result = executor.execute(SCRIPT_INCR, keys, args);
            return ((Number) result.get(IDX_INCR_COUNT)).longValue();
        } catch (RuntimeException ex) {
            LOG.warn("LOGIN failure record failed for client_ip={}: {}",
                clientIp, ex.getMessage());
            return RECORD_FAILED;
        }
    }

    /**
     * Outcome of a {@link #check} peek.
     *
     * @param decision the sliding-window peek decision (allow/deny)
     * @param failOpen {@code true} when the decision was produced by failing open
     *                 after a Redis error rather than a real peek result
     */
    public record LoginCheck(RateLimitDecision decision, boolean failOpen) {
    }
}
