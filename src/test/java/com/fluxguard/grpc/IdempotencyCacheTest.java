package com.fluxguard.grpc;

import com.fluxguard.exception.RedisUnavailableException;
import com.fluxguard.grpc.IdempotencyCache.Bypass;
import com.fluxguard.grpc.IdempotencyCache.Concurrent;
import com.fluxguard.grpc.IdempotencyCache.First;
import com.fluxguard.grpc.IdempotencyCache.Hit;
import com.fluxguard.grpc.IdempotencyCache.IdemLookup;
import com.fluxguard.model.RateLimitDecision;
import com.fluxguard.redis.LuaScriptExecutor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link IdempotencyCache}: reply-code parsing, decision encoding,
 * TTL selection, and fail-open behaviour. {@link LuaScriptExecutor} is mocked.
 */
class IdempotencyCacheTest {

    private static final String KEY = "rl:idem:acct-1:policy:transaction:abc";
    private static final long ALLOW_REMAINING = 5L;
    private static final long DENY_RESET_MS = 1200L;
    private static final long STORE_REMAINING = 7L;
    private static final long STORE_DENY_RESET_MS = 900L;

    private LuaScriptExecutor executor;
    private IdempotencyCache cache;

    @BeforeEach
    void setUp() {
        executor = mock(LuaScriptExecutor.class);
        cache = new IdempotencyCache(executor);
    }

    @Test
    void lookupReturnsFirstForCodeOne() {
        when(executor.execute(anyString(), anyList(), anyList())).thenReturn(List.of(1L));
        assertInstanceOf(First.class, cache.lookup(KEY));
    }

    @Test
    void lookupReturnsConcurrentForCodeTwo() {
        when(executor.execute(anyString(), anyList(), anyList())).thenReturn(List.of(2L));
        assertInstanceOf(Concurrent.class, cache.lookup(KEY));
    }

    @Test
    void lookupParsesAllowHit() {
        when(executor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(0L, "1:5:0"));

        final IdemLookup result = cache.lookup(KEY);

        final Hit hit = assertInstanceOf(Hit.class, result);
        assertTrue(hit.decision().allowed());
        assertEquals(ALLOW_REMAINING, hit.decision().remainingTokens());
    }

    @Test
    void lookupParsesDenyHit() {
        when(executor.execute(anyString(), anyList(), anyList()))
            .thenReturn(List.of(0L, "0:0:1200"));

        final IdemLookup result = cache.lookup(KEY);

        final Hit hit = assertInstanceOf(Hit.class, result);
        assertFalse(hit.decision().allowed());
        assertEquals(DENY_RESET_MS, hit.decision().resetAfterMs());
    }

    @Test
    void lookupFailsOpenToBypassOnExecutorError() {
        when(executor.execute(anyString(), anyList(), anyList()))
            .thenThrow(new RedisUnavailableException("down"));
        assertInstanceOf(Bypass.class, cache.lookup(KEY));
    }

    @Test
    void lookupSendsReserveTtlArg() {
        when(executor.execute(anyString(), anyList(), anyList())).thenReturn(List.of(1L));

        cache.lookup(KEY);

        verify(executor).execute(
            eq(IdempotencyCache.SCRIPT_RESERVE_OR_GET),
            eq(List.of(KEY)),
            eq(List.of(String.valueOf(IdempotencyCache.RESERVE_TTL_MS))));
    }

    @Test
    void storeAllowUsesAllowTtlAndEncodesDecision() {
        cache.store(KEY, RateLimitDecision.allow(STORE_REMAINING));

        final ArgumentCaptor<List<String>> args = captor();
        verify(executor).execute(eq(IdempotencyCache.SCRIPT_STORE), eq(List.of(KEY)), args.capture());
        assertEquals("1:7:0", args.getValue().get(0));
        assertEquals(String.valueOf(IdempotencyCache.ALLOW_TTL_MS), args.getValue().get(1));
    }

    @Test
    void storeDenyUsesResetAfterMsAsTtl() {
        cache.store(KEY, RateLimitDecision.deny(STORE_DENY_RESET_MS));

        final ArgumentCaptor<List<String>> args = captor();
        verify(executor).execute(eq(IdempotencyCache.SCRIPT_STORE), eq(List.of(KEY)), args.capture());
        assertEquals("0:0:900", args.getValue().get(0));
        assertEquals("900", args.getValue().get(1));
    }

    @Test
    void storeSwallowsExecutorError() {
        doThrow(new RedisUnavailableException("down"))
            .when(executor).execute(anyString(), anyList(), anyList());
        cache.store(KEY, RateLimitDecision.allow(1L));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<String>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
