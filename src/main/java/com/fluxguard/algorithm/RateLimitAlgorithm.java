package com.fluxguard.algorithm;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fluxguard.model.RateLimitDecision;
import java.util.List;

/**
 * Sealed strategy interface for rate-limiting algorithms.
 *
 * <p>Each permitted implementation encapsulates capacity parameters for one
 * algorithm (token bucket or sliding window) and provides the glue needed for
 * {@code RateLimitFilter} to execute the corresponding Redis Lua script atomically.
 *
 * <p>The interface is intentionally narrow: it covers only the production execution
 * path. Algorithm-specific logic (refill maths, window logic) lives in concrete
 * classes so it can be unit-tested without Redis.
 *
 * <p>{@code @JsonTypeInfo} and {@code @JsonSubTypes} enable Jackson to serialise the
 * concrete implementation to JSON (e.g. for {@code GET /admin/configs}) without a
 * custom serialiser. Deserialisation is handled by {@link com.fluxguard.config.RedisConfigService}
 * via a custom flat-JSON format and does not rely on these annotations.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TokenBucketAlgorithm.class, name = "token_bucket"),
    @JsonSubTypes.Type(value = SlidingWindowAlgorithm.class, name = "sliding_window")
})
public sealed interface RateLimitAlgorithm
        permits TokenBucketAlgorithm, SlidingWindowAlgorithm {

    /**
     * Returns the resource name of the Lua script that atomically applies this
     * algorithm against Redis state (without the {@code .lua} extension).
     *
     * @return Lua script identifier, e.g. {@code "token_bucket"}
     */
    String luaScriptName();

    /**
     * Builds the {@code KEYS} list forwarded to the Lua script.
     *
     * <p>Token bucket returns a single element (the bucket key).
     * Sliding window returns two elements (current-window key and previous-window key).
     * {@code LuaScriptExecutor} passes this list as the {@code KEYS} argument to EVAL.
     *
     * @param bucketKey  the base Redis key for this client/endpoint combination
     * @param nowMillis  current epoch time in milliseconds from {@code ClockProvider}
     * @return ordered list of Redis key names for the Lua script
     */
    List<String> buildLuaKeys(String bucketKey, long nowMillis);

    /**
     * Builds the {@code ARGV} array forwarded to the Lua script.
     *
     * <p>The returned list carries algorithm-specific parameters such as
     * capacity, refill rate, window duration, and current timestamp.
     *
     * @param nowMillis current epoch time in milliseconds from {@code ClockProvider}
     * @return ordered list of string arguments matching the script's expected ARGV
     */
    List<String> buildLuaArgs(long nowMillis);

    /**
     * Translates the raw multi-bulk reply returned by the Lua script into a
     * structured {@link RateLimitDecision}.
     *
     * <p>Implementations must tolerate {@code Long} elements, which is what
     * Spring Data Redis delivers for integer Lua return values.
     *
     * @param luaResult raw list returned by {@code LuaScriptExecutor}
     * @return a strongly typed decision record
     */
    RateLimitDecision parseResult(List<Object> luaResult);
}
