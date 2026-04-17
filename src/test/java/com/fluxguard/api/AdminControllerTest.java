package com.fluxguard.api;

import com.fluxguard.config.ConfigService;
import com.fluxguard.config.FeatureFlagService;
import com.fluxguard.config.LimitConfig;
import com.fluxguard.model.FeatureFlag;
import com.fluxguard.model.FeatureFlagRequest;
import com.fluxguard.model.LimitConfigRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminController}.
 *
 * <p>Pure Java — no Spring context, no Redis. All services are mocked.
 */
class AdminControllerTest {

    private static final String ENDPOINT         = "/api/search";
    private static final long   LIMIT            = 100L;
    private static final long   WINDOW_MS        = 60_000L;
    private static final long   CAPACITY         = 50L;
    private static final long   REFILL_RATE      = 10L;
    private static final int    DEFAULT_LIMIT    = 100;
    private static final int    OVER_MAX_LIMIT   = 5000;
    private static final int    MAX_AUDIT_LIMIT  = 1000;
    private static final int    ROLLOUT_PERCENT  = 50;
    private static final int    ROLLOUT_OVER_MAX = 101;

    private ConfigService      mockConfigService;
    private AuditService       mockAuditService;
    private FeatureFlagService mockFlagService;
    private HttpServletRequest mockRequest;
    private AdminController    controller;

    @BeforeEach
    void setUp() {
        mockConfigService = mock(ConfigService.class);
        mockAuditService  = mock(AuditService.class);
        mockFlagService   = mock(FeatureFlagService.class);
        mockRequest       = mock(HttpServletRequest.class);
        controller = new AdminController(mockConfigService, mockAuditService, mockFlagService);
    }

    // ── config tests ─────────────────────────────────────────────────────────

    @Test
    void getAllConfigsReturnsMapFromService() {
        final LimitConfig config = LimitConfig.slidingWindow(ENDPOINT, LIMIT, WINDOW_MS);
        when(mockConfigService.getAllConfigs()).thenReturn(Map.of(ENDPOINT, config));

        final ResponseEntity<Map<String, LimitConfig>> resp = controller.getAllConfigs();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
    }

    @Test
    void putConfigValidSlidingWindowDelegates() {
        final LimitConfigRequest req = new LimitConfigRequest(
            "sliding_window", null, null, LIMIT, WINDOW_MS);

        final ResponseEntity<Void> resp = controller.putConfig(ENDPOINT, req, mockRequest);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(mockConfigService).putConfig(eq(ENDPOINT), any(LimitConfig.class));
    }

    @Test
    void putConfigValidTokenBucketDelegates() {
        final LimitConfigRequest req = new LimitConfigRequest(
            "token_bucket", CAPACITY, REFILL_RATE, null, null);

        final ResponseEntity<Void> resp = controller.putConfig(ENDPOINT, req, mockRequest);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(mockConfigService).putConfig(eq(ENDPOINT), any(LimitConfig.class));
    }

    @Test
    void putConfigUnknownAlgorithmReturns400() {
        final LimitConfigRequest req = new LimitConfigRequest(
            "mystery_algo", null, null, null, null);

        final ResponseEntity<Void> resp = controller.putConfig(ENDPOINT, req, mockRequest);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(mockConfigService, never()).putConfig(any(), any());
    }

    @Test
    void putConfigTokenBucketMissingCapacityReturns400() {
        final LimitConfigRequest req = new LimitConfigRequest(
            "token_bucket", null, REFILL_RATE, null, null);

        final ResponseEntity<Void> resp = controller.putConfig(ENDPOINT, req, mockRequest);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(mockConfigService, never()).putConfig(any(), any());
    }

    @Test
    void putConfigSlidingWindowMissingLimitReturns400() {
        final LimitConfigRequest req = new LimitConfigRequest(
            "sliding_window", null, null, null, WINDOW_MS);

        final ResponseEntity<Void> resp = controller.putConfig(ENDPOINT, req, mockRequest);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(mockConfigService, never()).putConfig(any(), any());
    }

    @Test
    void removeConfigDelegates() {
        final ResponseEntity<Void> resp = controller.removeConfig(ENDPOINT, mockRequest);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(mockConfigService).removeConfig(ENDPOINT);
    }

    @Test
    void activateKillSwitchSetsTrue() {
        final ResponseEntity<Void> resp = controller.activateKillSwitch(mockRequest);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(mockConfigService).setKillSwitch(true);
    }

    @Test
    void deactivateKillSwitchSetsFalse() {
        final ResponseEntity<Void> resp = controller.deactivateKillSwitch(mockRequest);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(mockConfigService).setKillSwitch(false);
    }

    // ── feature flag tests ───────────────────────────────────────────────────

    @Test
    void getAllFlagsReturnsMapFromService() {
        final FeatureFlag flag = new FeatureFlag(ENDPOINT, true, false, ROLLOUT_PERCENT, null);
        when(mockFlagService.getAllFlags()).thenReturn(Map.of(ENDPOINT, flag));

        final ResponseEntity<Map<String, FeatureFlag>> resp = controller.getAllFlags();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
    }

    @Test
    void putFlagNoOverrideDelegates() {
        final FeatureFlagRequest req = new FeatureFlagRequest(
            true, false, ROLLOUT_PERCENT, null, null, null, null, null);

        final ResponseEntity<Void> resp = controller.putFlag(ENDPOINT, req, mockRequest);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(mockFlagService).putFlag(eq(ENDPOINT), any(FeatureFlag.class));
    }

    @Test
    void putFlagWithTokenBucketOverrideDelegates() {
        final FeatureFlagRequest req = new FeatureFlagRequest(
            true, true, ROLLOUT_PERCENT, "token_bucket", CAPACITY, REFILL_RATE, null, null);

        final ResponseEntity<Void> resp = controller.putFlag(ENDPOINT, req, mockRequest);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(mockFlagService).putFlag(eq(ENDPOINT), any(FeatureFlag.class));
    }

    @Test
    void putFlagInvalidAlgorithmReturns400() {
        final FeatureFlagRequest req = new FeatureFlagRequest(
            true, false, ROLLOUT_PERCENT, "mystery_algo", null, null, null, null);

        final ResponseEntity<Void> resp = controller.putFlag(ENDPOINT, req, mockRequest);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(mockFlagService, never()).putFlag(any(), any());
    }

    @Test
    void putFlagTokenBucketMissingCapacityReturns400() {
        final FeatureFlagRequest req = new FeatureFlagRequest(
            true, false, ROLLOUT_PERCENT, "token_bucket", null, REFILL_RATE, null, null);

        final ResponseEntity<Void> resp = controller.putFlag(ENDPOINT, req, mockRequest);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(mockFlagService, never()).putFlag(any(), any());
    }

    @Test
    void putFlagRolloutOverMaxReturns400() {
        final FeatureFlagRequest req = new FeatureFlagRequest(
            true, false, ROLLOUT_OVER_MAX, null, null, null, null, null);

        final ResponseEntity<Void> resp = controller.putFlag(ENDPOINT, req, mockRequest);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(mockFlagService, never()).putFlag(any(), any());
    }

    @Test
    void removeFlagDelegates() {
        final ResponseEntity<Void> resp = controller.removeFlag(ENDPOINT, mockRequest);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(mockFlagService).removeFlag(ENDPOINT);
    }

    @Test
    void putFlagAuditsSuccess() {
        final FeatureFlagRequest req = new FeatureFlagRequest(
            true, false, ROLLOUT_PERCENT, null, null, null, null, null);

        controller.putFlag(ENDPOINT, req, mockRequest);

        verify(mockAuditService).record(eq("PUT_FLAG"), eq(ENDPOINT), any(), any());
    }

    @Test
    void removeFlagAuditsSuccess() {
        controller.removeFlag(ENDPOINT, mockRequest);

        verify(mockAuditService).record(eq("REMOVE_FLAG"), eq(ENDPOINT), any(), any());
    }

    // ── audit wiring tests ───────────────────────────────────────────────────

    @Test
    void putConfigValidTokenBucketAuditsSuccess() {
        final LimitConfigRequest req = new LimitConfigRequest(
            "token_bucket", CAPACITY, REFILL_RATE, null, null);

        controller.putConfig(ENDPOINT, req, mockRequest);

        verify(mockAuditService).record(eq("PUT_CONFIG"), eq(ENDPOINT),
            contains("token_bucket"), any());
    }

    @Test
    void putConfigInvalidAlgoDoesNotAudit() {
        final LimitConfigRequest req = new LimitConfigRequest(
            "mystery_algo", null, null, null, null);

        controller.putConfig(ENDPOINT, req, mockRequest);

        verify(mockAuditService, never()).record(any(), any(), any(), any());
    }

    @Test
    void removeConfigAuditsSuccess() {
        controller.removeConfig(ENDPOINT, mockRequest);

        verify(mockAuditService).record(eq("REMOVE_CONFIG"), eq(ENDPOINT), any(), any());
    }

    @Test
    void activateKillSwitchAuditsSuccess() {
        controller.activateKillSwitch(mockRequest);

        verify(mockAuditService).record(eq("ACTIVATE_KILL_SWITCH"), eq("global"), any(), any());
    }

    @Test
    void deactivateKillSwitchAuditsSuccess() {
        controller.deactivateKillSwitch(mockRequest);

        verify(mockAuditService).record(eq("DEACTIVATE_KILL_SWITCH"), eq("global"), any(), any());
    }

    @Test
    void getAuditLogDelegatesWithDefaultLimit() {
        when(mockAuditService.getRecent(DEFAULT_LIMIT)).thenReturn(List.of());

        final ResponseEntity<List<String>> resp = controller.getAuditLog(DEFAULT_LIMIT);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(mockAuditService).getRecent(DEFAULT_LIMIT);
    }

    @Test
    void getAuditLogCapsAtOneThousand() {
        controller.getAuditLog(OVER_MAX_LIMIT);

        verify(mockAuditService).getRecent(MAX_AUDIT_LIMIT);
    }

    @Test
    void getAuditLogActorComesFromRequestAttribute() {
        when(mockRequest.getAttribute("X-Admin-Actor")).thenReturn("admin");

        controller.activateKillSwitch(mockRequest);

        verify(mockAuditService).record(any(), any(), any(), eq("admin"));
    }
}
