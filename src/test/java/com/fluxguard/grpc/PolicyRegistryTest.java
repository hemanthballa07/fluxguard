package com.fluxguard.grpc;

import com.fluxguard.config.ConfigService;
import com.fluxguard.config.LimitConfig;
import com.fluxguard.grpc.PolicyRegistry.ResolvedPolicy;
import com.fluxguard.grpc.ratelimit.v1.Policy;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link PolicyRegistry}: config-key mapping, defaults, stored
 * overrides, and rejection of unsupported policies. {@link ConfigService} is mocked.
 */
class PolicyRegistryTest {

    private ConfigService configService;
    private PolicyRegistry registry;

    @BeforeEach
    void setUp() {
        configService = mock(ConfigService.class);
        when(configService.getConfig(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        registry = new PolicyRegistry(configService);
    }

    @Test
    void transactionResolvesToKeyAndTokenBucketDefault() {
        final ResolvedPolicy rp = registry.resolve(Policy.POLICY_TRANSACTION).orElseThrow();
        assertEquals(PolicyRegistry.KEY_TRANSACTION, rp.configKey());
        assertEquals(PolicyRegistry.KEY_TRANSACTION, rp.config().endpointPattern());
        assertEquals("token_bucket", rp.config().algorithm().luaScriptName());
    }

    @Test
    void opsReleaseResolvesToKeyAndTokenBucketDefault() {
        final ResolvedPolicy rp = registry.resolve(Policy.POLICY_OPS_RELEASE).orElseThrow();
        assertEquals(PolicyRegistry.KEY_OPS_RELEASE, rp.configKey());
        assertEquals("token_bucket", rp.config().algorithm().luaScriptName());
    }

    @Test
    void opsRejectResolvesToKeyAndTokenBucketDefault() {
        final ResolvedPolicy rp = registry.resolve(Policy.POLICY_OPS_REJECT).orElseThrow();
        assertEquals(PolicyRegistry.KEY_OPS_REJECT, rp.configKey());
        assertEquals("token_bucket", rp.config().algorithm().luaScriptName());
    }

    @Test
    void loginResolvesToKeyAndSlidingWindowDefault() {
        final ResolvedPolicy rp = registry.resolve(Policy.POLICY_LOGIN).orElseThrow();
        assertEquals(PolicyRegistry.KEY_LOGIN, rp.configKey());
        assertEquals("sliding_window", rp.config().algorithm().luaScriptName());
    }

    @Test
    void storedConfigOverridesDefaultWhenPresent() {
        final LimitConfig stored = LimitConfig.tokenBucket(PolicyRegistry.KEY_TRANSACTION, 99L, 9L);
        when(configService.getConfig(PolicyRegistry.KEY_TRANSACTION))
            .thenReturn(Optional.of(stored));

        final ResolvedPolicy rp = registry.resolve(Policy.POLICY_TRANSACTION).orElseThrow();

        assertSame(stored, rp.config());
    }

    @Test
    void unspecifiedPolicyResolvesToEmpty() {
        assertTrue(registry.resolve(Policy.POLICY_UNSPECIFIED).isEmpty());
    }

    @Test
    void unrecognizedPolicyResolvesToEmpty() {
        assertTrue(registry.resolve(Policy.UNRECOGNIZED).isEmpty());
    }
}
