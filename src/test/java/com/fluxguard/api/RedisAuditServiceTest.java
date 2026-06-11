package com.fluxguard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisAuditService}.
 *
 * <p>Pure Java — no Spring context. Redis operations are mocked.
 */
@SuppressWarnings("unchecked")
class RedisAuditServiceTest {

    private static final long MAX_LOG_SIZE  = 10_000L;
    private static final long RECENT_COUNT  = 50L;

    private ListOperations<String, String> listOps;
    private StringRedisTemplate redis;
    private RedisAuditService service;

    @BeforeEach
    void setUp() {
        listOps = mock(ListOperations.class);
        redis   = mock(StringRedisTemplate.class);
        when(redis.opsForList()).thenReturn(listOps);
        service = new RedisAuditService(redis, new ObjectMapper());
    }

    @Test
    void recordCallsRightPushAndTrimWithCorrectArgs() {
        service.record("PUT_CONFIG", "/api/search", "sliding_window", "admin");

        verify(listOps).rightPush(
            eq(RedisAuditService.AUDIT_LOG_KEY),
            argThat(json -> json.contains("\"action\":\"PUT_CONFIG\"")));
        verify(listOps).trim(
            eq(RedisAuditService.AUDIT_LOG_KEY), eq(-MAX_LOG_SIZE), eq(-1L));
    }

    @Test
    void recordWithNullActorUsesUnknown() {
        service.record("PUT_CONFIG", "/api/search", "sliding_window", null);

        verify(listOps).rightPush(
            eq(RedisAuditService.AUDIT_LOG_KEY),
            argThat(json -> json.contains("\"actor\":\"unknown\"")));
    }

    @Test
    void recordRedisFailureDoesNotThrow() {
        doThrow(new RuntimeException("Redis down"))
            .when(listOps).rightPush(any(), any());

        assertDoesNotThrow(() ->
            service.record("PUT_CONFIG", "/api/search", "sliding_window", "admin"));
    }

    @Test
    void getRecentDelegatesToRangeWithNegativeIndices() {
        final List<String> entries = List.of("entry1", "entry2");
        when(listOps.range(RedisAuditService.AUDIT_LOG_KEY, -RECENT_COUNT, -1L))
            .thenReturn(entries);

        final List<String> result = service.getRecent((int) RECENT_COUNT);

        assertEquals(entries, result);
    }

    @Test
    void getRecentRedisFailureReturnsEmptyList() {
        when(listOps.range(any(), anyLong(), anyLong()))
            .thenThrow(new RuntimeException("Redis down"));

        final List<String> result = service.getRecent(10);

        assertEquals(Collections.emptyList(), result);
    }

    @Test
    void getRecentNullResultFromRedisReturnsEmptyList() {
        when(listOps.range(any(), anyLong(), anyLong())).thenReturn(null);

        final List<String> result = service.getRecent(10);

        assertTrue(result.isEmpty());
    }

    @Test
    void recordWithBlankActorUsesUnknown() {
        service.record("PUT_CONFIG", "/api/search", "sliding_window", "   ");

        verify(listOps).rightPush(
            eq(RedisAuditService.AUDIT_LOG_KEY),
            argThat(json -> json.contains("\"actor\":\"unknown\"")));
    }

    @Test
    void recordTrimFailureDoesNotThrow() {
        doThrow(new RuntimeException("Redis down"))
            .when(listOps).trim(any(), anyLong(), anyLong());

        assertDoesNotThrow(() ->
            service.record("PUT_CONFIG", "/api/search", "sliding_window", "admin"));
    }
}
