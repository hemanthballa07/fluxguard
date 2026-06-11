package com.fluxguard.grpc;

import com.fluxguard.config.LimitConfig;
import com.fluxguard.grpc.LoginThrottle.LoginCheck;
import com.fluxguard.redis.LuaScriptExecutor;
import com.fluxguard.util.ClockProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link LoginThrottle}. The {@link LuaScriptExecutor} and
 * {@link ClockProvider} are mocked — no Spring, no Redis.
 */
class LoginThrottleTest {

    private static final String CLIENT_IP = "203.0.113.7";
    private static final long NOW_MS = 1_000_000L;
    private static final long LOGIN_LIMIT = 5L;
    private static final long LOGIN_WINDOW_MS = 300_000L;
    private static final long REMAINING = 3L;
    private static final long RESET_AFTER_MS = 42_000L;
    private static final long INCR_COUNT = 4L;
    private static final long RECORD_FAILED = -1L;

    private LuaScriptExecutor executor;
    private ClockProvider clock;
    private LoginThrottle throttle;
    private LimitConfig config;

    @BeforeEach
    void setUp() {
        executor = mock(LuaScriptExecutor.class);
        clock = mock(ClockProvider.class);
        when(clock.nowMillis()).thenReturn(NOW_MS);
        throttle = new LoginThrottle(executor, clock);
        config = LimitConfig.slidingWindow("policy:login", LOGIN_LIMIT, LOGIN_WINDOW_MS);
    }

    @Test
    void checkAllowsWhenPeekUnderLimit() {
        when(executor.execute(eq(LoginThrottle.SCRIPT_PEEK), any(), any()))
            .thenReturn(List.of(1L, REMAINING, 0L));

        final LoginCheck result = throttle.check(CLIENT_IP, config);

        assertTrue(result.decision().allowed());
        assertEquals(REMAINING, result.decision().remainingTokens());
        assertFalse(result.failOpen());
    }

    @Test
    void checkDeniesWhenPeekAtLimit() {
        when(executor.execute(eq(LoginThrottle.SCRIPT_PEEK), any(), any()))
            .thenReturn(List.of(0L, 0L, RESET_AFTER_MS));

        final LoginCheck result = throttle.check(CLIENT_IP, config);

        assertFalse(result.decision().allowed());
        assertEquals(RESET_AFTER_MS, result.decision().resetAfterMs());
        assertFalse(result.failOpen());
    }

    @Test
    void checkFailsOpenWhenExecutorThrows() {
        when(executor.execute(eq(LoginThrottle.SCRIPT_PEEK), any(), any()))
            .thenThrow(new RuntimeException("redis down"));

        final LoginCheck result = throttle.check(CLIENT_IP, config);

        assertTrue(result.decision().allowed());
        assertTrue(result.failOpen());
    }

    @Test
    void recordFailureReturnsIncrementCount() {
        when(executor.execute(eq(LoginThrottle.SCRIPT_INCR), any(), any()))
            .thenReturn(List.of(INCR_COUNT));

        assertEquals(INCR_COUNT, throttle.recordFailure(CLIENT_IP, config));
    }

    @Test
    void recordFailureSwallowsErrorAndReturnsSentinel() {
        when(executor.execute(eq(LoginThrottle.SCRIPT_INCR), any(), any()))
            .thenThrow(new RuntimeException("redis down"));

        assertEquals(RECORD_FAILED, throttle.recordFailure(CLIENT_IP, config));
    }
}
