package com.fluxguard.redis;

import com.fluxguard.exception.RedisUnavailableException;
import com.fluxguard.metrics.PrometheusMetricsCollector;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Executes Redis Lua scripts loaded from the classpath.
 *
 * <p>All Redis interactions for rate limiting are funnelled through this class.
 * Each call issues a single atomic EVAL / EVALSHA command — no raw Redis commands
 * are used directly. This ensures rate-limit decisions are always atomic even
 * under concurrent load.
 *
 * <p>Script files are resolved from {@code classpath:lua/{scriptName}.lua}.
 * Parsed {@link RedisScript} instances are cached in a {@link ConcurrentHashMap}
 * so the SHA-1 digest is computed only once per script name per JVM lifetime.
 *
 * <p><b>Failure contract:</b> callers (i.e. {@code RateLimitFilter}) are responsible
 * for catching exceptions thrown by this class and implementing fail-open logic.
 * If Redis returns {@code null}, this class throws {@link RedisUnavailableException}
 * rather than propagating a {@code NullPointerException} to callers.
 */
@Component
public class LuaScriptExecutor {

    /** Classpath directory that holds all Lua script resources. */
    private static final String SCRIPT_DIR = "lua/";

    /** File extension appended to the script name when resolving from classpath. */
    private static final String SCRIPT_EXT = ".lua";

    /** Span name used for Redis Lua script execution traces. */
    private static final String SPAN_REDIS_SCRIPT = "redis.lua_script";

    /** Span attribute key for the Lua script name. */
    private static final String TAG_SCRIPT_NAME = "script_name";

    /** Span attribute key for the number of Redis keys sent to the script. */
    private static final String TAG_KEY_COUNT = "key_count";

    private final StringRedisTemplate redisTemplate;
    private final PrometheusMetricsCollector metrics;
    private final Tracer tracer;

    /**
     * Thread-safe cache of compiled {@link RedisScript} instances, keyed by script name.
     * Populated lazily via {@link ConcurrentHashMap#computeIfAbsent}.
     */
    @SuppressWarnings("rawtypes")
    private final Map<String, RedisScript<List>> scriptCache = new ConcurrentHashMap<>();

    /**
     * Constructs a {@code LuaScriptExecutor} with the given Redis template, metrics collector,
     * and tracer.
     *
     * @param redisTemplate Spring Data Redis template configured with string
     *                      serializers; must not be {@code null}
     * @param metrics       collector used to record Redis script execution duration
     * @param tracer        tracer used to create Redis script execution spans
     */
    public LuaScriptExecutor(
            final StringRedisTemplate redisTemplate,
            final PrometheusMetricsCollector metrics,
            final Tracer tracer) {
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    /**
     * Loads and executes the named Lua script against Redis, returning the
     * raw multi-bulk reply.
     *
     * <p>The {@link RedisScript} instance is created once per script name and
     * cached for subsequent calls. Spring Data Redis handles EVALSHA promotion
     * automatically after the first successful EVAL round-trip.
     *
     * <p>Return values follow Redis Lua conventions: integer values in the Lua
     * return table arrive as {@code Long} elements in the resulting {@code List}.
     *
     * @param scriptName resource name of the Lua script (without {@code .lua} extension)
     * @param keys       Redis key names forwarded as the KEYS argument to EVAL
     * @param args       string arguments forwarded as the ARGV argument to EVAL
     * @return raw multi-bulk reply from the Lua script as a {@code List<Object>}
     *         where integer Lua values are represented as {@link Long}
     * @throws RedisUnavailableException if Redis returns {@code null} or is unreachable
     */
    @SuppressWarnings("unchecked")
    public List<Object> execute(
            final String scriptName,
            final List<String> keys,
            final List<String> args) {
        final RedisScript<List> script = resolveScript(scriptName);
        final Span span = tracer.spanBuilder(SPAN_REDIS_SCRIPT)
            .setSpanKind(SpanKind.CLIENT)
            .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute(TAG_SCRIPT_NAME, scriptName);
            span.setAttribute(TAG_KEY_COUNT, keys.size());
            final long startNs = System.nanoTime();
            final List<Object> result =
                (List<Object>) redisTemplate.execute(script, keys, args.toArray());
            metrics.recordScriptDuration(scriptName, System.nanoTime() - startNs);
            if (result == null) {
                span.setStatus(StatusCode.ERROR, "Redis returned null");
                throw new RedisUnavailableException(
                    "Redis returned null for script: " + scriptName);
            }
            return result;
        } finally {
            span.end();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RedisScript<List> resolveScript(final String scriptName) {
        return scriptCache.computeIfAbsent(
            scriptName,
            name -> RedisScript.of(loadScriptResource(name), List.class));
    }

    private ClassPathResource loadScriptResource(final String scriptName) {
        return new ClassPathResource(SCRIPT_DIR + scriptName + SCRIPT_EXT);
    }
}
