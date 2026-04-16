package com.fluxguard.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Central Micrometer metrics facade for all rate-limiting observability signals.
 *
 * <p>All five metric families are registered lazily on first use.
 * Micrometer caches by name + tag set, so repeated calls with the same tags are O(1).
 *
 * <p>Metric families:
 * <ul>
 *   <li>{@code rate_limit_allowed_total} — counter, tags: endpoint, algorithm</li>
 *   <li>{@code rate_limit_denied_total} — counter, tags: endpoint, algorithm</li>
 *   <li>{@code rate_limit_failopen_total} — counter, tags: endpoint, reason</li>
 *   <li>{@code rate_limit_duration_seconds} — timer, tags: endpoint, algorithm, result</li>
 *   <li>{@code redis_script_duration_seconds} — timer, tags: script_name</li>
 * </ul>
 *
 * <p>The Prometheus registry appends {@code _total} to counter names and
 * {@code _seconds} to timer names automatically; base names must not include those suffixes.
 */
@Component
public class PrometheusMetricsCollector {

    // ── metric names ─────────────────────────────────────────────────────────

    /** Micrometer name for the allowed-request counter. */
    public static final String METRIC_ALLOWED      = "rate.limit.allowed";

    /** Micrometer name for the denied-request counter. */
    public static final String METRIC_DENIED       = "rate.limit.denied";

    /** Micrometer name for the fail-open counter. */
    public static final String METRIC_FAILOPEN     = "rate.limit.failopen";

    /** Micrometer name for the per-decision duration histogram. */
    public static final String METRIC_DECISION_DUR = "rate.limit.duration";

    /** Micrometer name for the Redis script execution duration histogram. */
    public static final String METRIC_SCRIPT_DUR   = "redis.script.duration";

    // ── tag keys ──────────────────────────────────────────────────────────────

    /** Tag key identifying the HTTP endpoint. */
    public static final String TAG_ENDPOINT  = "endpoint";

    /** Tag key identifying the rate-limiting algorithm. */
    public static final String TAG_ALGORITHM = "algorithm";

    /** Tag key identifying the fail-open trigger reason. */
    public static final String TAG_REASON    = "reason";

    /** Tag key identifying the outcome of a rate-limit decision. */
    public static final String TAG_RESULT    = "result";

    /** Tag key identifying the Lua script that was executed. */
    public static final String TAG_SCRIPT    = "script_name";

    // ── result label values ───────────────────────────────────────────────────

    /** Result label for a request that was allowed by a real rate-limit decision. */
    public static final String RESULT_ALLOWED  = "allowed";

    /** Result label for a request that was denied by a real rate-limit decision. */
    public static final String RESULT_DENIED   = "denied";

    /** Result label for a request that was allowed via fail-open (no real decision). */
    public static final String RESULT_FAILOPEN = "failopen";

    // ── reason label values ───────────────────────────────────────────────────

    /** Fail-open reason when a {@link RuntimeException} from Redis triggered the path. */
    public static final String REASON_REDIS_ERROR   = "redis_error";

    /** Fail-open reason when an open circuit breaker short-circuited the Redis call. */
    public static final String REASON_CIRCUIT_OPEN  = "circuit_open";

    private final MeterRegistry registry;

    /**
     * Constructs the collector with the given Micrometer registry.
     *
     * @param registry the application's {@link MeterRegistry}; must not be {@code null}
     */
    public PrometheusMetricsCollector(final MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Increments the allowed-request counter for the given endpoint and algorithm.
     *
     * @param endpoint  HTTP path of the rate-limited endpoint
     * @param algorithm name of the algorithm that produced the decision (e.g. {@code "token_bucket"})
     */
    public void recordAllowed(final String endpoint, final String algorithm) {
        Counter.builder(METRIC_ALLOWED)
            .tag(TAG_ENDPOINT, endpoint)
            .tag(TAG_ALGORITHM, algorithm)
            .description("Rate-limit decisions that allowed the request")
            .register(registry)
            .increment();
    }

    /**
     * Increments the denied-request counter for the given endpoint and algorithm.
     *
     * @param endpoint  HTTP path of the rate-limited endpoint
     * @param algorithm name of the algorithm that produced the decision
     */
    public void recordDenied(final String endpoint, final String algorithm) {
        Counter.builder(METRIC_DENIED)
            .tag(TAG_ENDPOINT, endpoint)
            .tag(TAG_ALGORITHM, algorithm)
            .description("Rate-limit decisions that denied the request")
            .register(registry)
            .increment();
    }

    /**
     * Increments the fail-open counter for the given endpoint and trigger reason.
     *
     * @param endpoint HTTP path of the rate-limited endpoint
     * @param reason   {@link #REASON_REDIS_ERROR} or {@link #REASON_CIRCUIT_OPEN}
     */
    public void recordFailOpen(final String endpoint, final String reason) {
        Counter.builder(METRIC_FAILOPEN)
            .tag(TAG_ENDPOINT, endpoint)
            .tag(TAG_REASON, reason)
            .description("Requests allowed due to Redis unavailability or open circuit breaker")
            .register(registry)
            .increment();
    }

    /**
     * Records the duration of a complete rate-limit decision cycle.
     *
     * <p>The {@code result} tag distinguishes allowed, denied, and fail-open outcomes
     * so latency can be analysed per outcome class.
     *
     * @param endpoint  HTTP path of the rate-limited endpoint
     * @param algorithm name of the algorithm used for the decision
     * @param result    one of {@link #RESULT_ALLOWED}, {@link #RESULT_DENIED},
     *                  or {@link #RESULT_FAILOPEN}
     * @param nanos     elapsed wall-clock nanoseconds measured by the caller
     */
    public void recordDecisionDuration(
            final String endpoint,
            final String algorithm,
            final String result,
            final long nanos) {
        Timer.builder(METRIC_DECISION_DUR)
            .tag(TAG_ENDPOINT, endpoint)
            .tag(TAG_ALGORITHM, algorithm)
            .tag(TAG_RESULT, result)
            .description("Duration of a rate-limit decision from script call to response")
            .publishPercentileHistogram()
            .register(registry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Records the duration of a single Redis Lua script execution.
     *
     * <p>Only the {@code redisTemplate.execute()} call is timed — script loading
     * and cache lookup are excluded.
     *
     * @param scriptName the script identifier (without {@code .lua} extension)
     * @param nanos      elapsed wall-clock nanoseconds measured by the caller
     */
    public void recordScriptDuration(final String scriptName, final long nanos) {
        Timer.builder(METRIC_SCRIPT_DUR)
            .tag(TAG_SCRIPT, scriptName)
            .description("Duration of a Redis Lua script execution via redisTemplate.execute()")
            .publishPercentileHistogram()
            .register(registry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }
}
