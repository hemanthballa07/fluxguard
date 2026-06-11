package com.fluxguard.grpc;

import com.fluxguard.config.ConfigService;
import com.fluxguard.config.LimitConfig;
import com.fluxguard.grpc.ratelimit.v1.Policy;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps gRPC {@link Policy} values onto stored {@link LimitConfig} entries.
 *
 * <p>Each supported policy resolves to a stable config key (e.g.
 * {@code "policy:transaction"}). The live limit is read from {@link ConfigService};
 * when no override is stored, a hard-coded default keeps the service functional
 * before any dynamic configuration is pushed.
 *
 * <p>{@link Policy#POLICY_UNSPECIFIED}, {@link Policy#UNRECOGNIZED}, and any value
 * without a mapping resolve to {@link Optional#empty()} so callers can reject the
 * request as invalid.
 */
@Component
public class PolicyRegistry {

    /** Config key for the transaction-throughput policy. */
    static final String KEY_TRANSACTION = "policy:transaction";

    /** Config key for the ops-release policy. */
    static final String KEY_OPS_RELEASE = "policy:ops_release";

    /** Config key for the ops-reject policy. */
    static final String KEY_OPS_REJECT = "policy:ops_reject";

    /** Config key for the login policy. */
    static final String KEY_LOGIN = "policy:login";

    /** Default token-bucket capacity for the transaction policy. */
    private static final long TRANSACTION_CAPACITY = 20L;

    /** Default token-bucket refill rate (tokens/sec) for the transaction policy. */
    private static final long TRANSACTION_REFILL = 5L;

    /** Default token-bucket capacity for the ops policies. */
    private static final long OPS_CAPACITY = 10L;

    /** Default token-bucket refill rate (tokens/sec) for the ops policies. */
    private static final long OPS_REFILL = 2L;

    /** Default sliding-window request limit for the login policy. */
    private static final long LOGIN_LIMIT = 5L;

    /** Default sliding-window duration (ms) for the login policy. */
    private static final long LOGIN_WINDOW_MS = 300_000L;

    private final ConfigService configService;

    /**
     * Constructs the registry with the backing config store.
     *
     * @param configService source of dynamically stored per-policy limits
     */
    public PolicyRegistry(final ConfigService configService) {
        this.configService = configService;
    }

    /**
     * Resolves a gRPC policy to its config key and effective {@link LimitConfig}.
     *
     * <p>The stored config (if any) wins; otherwise a per-policy default is used.
     * Unknown or unspecified policies resolve to {@link Optional#empty()}.
     *
     * @param policy the requested rate-limit policy
     * @return the resolved policy, or empty when the policy is not supported
     */
    public Optional<ResolvedPolicy> resolve(final Policy policy) {
        final String configKey = configKeyFor(policy);
        if (configKey == null) {
            return Optional.empty();
        }
        final LimitConfig config = configService.getConfig(configKey)
            .orElseGet(() -> defaultFor(policy, configKey));
        return Optional.of(new ResolvedPolicy(configKey, config));
    }

    private static String configKeyFor(final Policy policy) {
        return switch (policy) {
            case POLICY_TRANSACTION -> KEY_TRANSACTION;
            case POLICY_OPS_RELEASE -> KEY_OPS_RELEASE;
            case POLICY_OPS_REJECT -> KEY_OPS_REJECT;
            case POLICY_LOGIN -> KEY_LOGIN;
            default -> null;
        };
    }

    private static LimitConfig defaultFor(final Policy policy, final String configKey) {
        return switch (policy) {
            case POLICY_TRANSACTION ->
                LimitConfig.tokenBucket(configKey, TRANSACTION_CAPACITY, TRANSACTION_REFILL);
            case POLICY_OPS_RELEASE, POLICY_OPS_REJECT ->
                LimitConfig.tokenBucket(configKey, OPS_CAPACITY, OPS_REFILL);
            case POLICY_LOGIN ->
                LimitConfig.slidingWindow(configKey, LOGIN_LIMIT, LOGIN_WINDOW_MS);
            default ->
                throw new IllegalStateException("no default for policy: " + policy);
        };
    }

    /**
     * A policy resolved to its config key and the limit configuration to apply.
     *
     * @param configKey stable key identifying the policy in the config store
     * @param config     the effective limit configuration (stored override or default)
     */
    public record ResolvedPolicy(String configKey, LimitConfig config) {
    }
}
