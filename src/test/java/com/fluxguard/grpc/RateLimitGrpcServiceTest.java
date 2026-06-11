package com.fluxguard.grpc;

import com.fluxguard.engine.DecisionOutcome;
import com.fluxguard.engine.RateLimitEngine;
import com.fluxguard.grpc.IdempotencyCache.Bypass;
import com.fluxguard.grpc.IdempotencyCache.Concurrent;
import com.fluxguard.grpc.IdempotencyCache.First;
import com.fluxguard.grpc.IdempotencyCache.Hit;
import com.fluxguard.grpc.LoginThrottle.LoginCheck;
import com.fluxguard.grpc.PolicyRegistry.ResolvedPolicy;
import com.fluxguard.grpc.ratelimit.v1.CheckLimitRequest;
import com.fluxguard.grpc.ratelimit.v1.CheckLimitResponse;
import com.fluxguard.grpc.ratelimit.v1.Decision;
import com.fluxguard.grpc.ratelimit.v1.Policy;
import com.fluxguard.grpc.ratelimit.v1.ReportLoginFailureRequest;
import com.fluxguard.grpc.ratelimit.v1.ReportLoginFailureResponse;
import com.fluxguard.config.LimitConfig;
import com.fluxguard.metrics.PrometheusMetricsCollector;
import com.fluxguard.model.RateLimitDecision;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RateLimitGrpcService}. Collaborators are mocked and a
 * small {@link TestStreamObserver} captures {@code onNext}/{@code onError}. No Spring,
 * no Redis, no real gRPC server.
 */
class RateLimitGrpcServiceTest {

    private static final String SUBJECT = "acct-42";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String CONFIG_KEY = PolicyRegistry.KEY_TRANSACTION;
    private static final String LOGIN_KEY = PolicyRegistry.KEY_LOGIN;
    private static final long REMAINING = 11L;
    private static final long RETRY_MS = 750L;
    private static final long TB_CAPACITY = 20L;
    private static final long TB_REFILL = 5L;
    private static final long LOGIN_LIMIT = 5L;
    private static final long LOGIN_WINDOW_MS = 300_000L;
    private static final long LOGIN_REMAINING = 3L;
    private static final long LOGIN_RESET_MS = 42_000L;
    private static final long FAILURES_IN_WINDOW = 4L;
    private static final int UNKNOWN_POLICY_VALUE = 99;

    private PolicyRegistry registry;
    private RateLimitEngine engine;
    private IdempotencyCache idemCache;
    private LoginThrottle loginThrottle;
    private RateLimitGrpcService service;
    private TestStreamObserver<CheckLimitResponse> obs;
    private ResolvedPolicy resolved;
    private ResolvedPolicy resolvedLogin;

    @BeforeEach
    void setUp() {
        registry = mock(PolicyRegistry.class);
        engine = mock(RateLimitEngine.class);
        idemCache = mock(IdempotencyCache.class);
        loginThrottle = mock(LoginThrottle.class);
        service = new RateLimitGrpcService(registry, engine, idemCache, loginThrottle);
        obs = new TestStreamObserver<>();
        resolved = new ResolvedPolicy(CONFIG_KEY,
            LimitConfig.tokenBucket(CONFIG_KEY, TB_CAPACITY, TB_REFILL));
        resolvedLogin = new ResolvedPolicy(LOGIN_KEY,
            LimitConfig.slidingWindow(LOGIN_KEY, LOGIN_LIMIT, LOGIN_WINDOW_MS));
        when(registry.resolve(Policy.POLICY_TRANSACTION)).thenReturn(Optional.of(resolved));
        when(registry.resolve(Policy.POLICY_LOGIN)).thenReturn(Optional.of(resolvedLogin));
    }

    private static CheckLimitRequest request(final Policy policy, final String idem) {
        return CheckLimitRequest.newBuilder()
            .setPolicy(policy).setSubject(SUBJECT).setIdempotencyKey(idem).build();
    }

    private static CheckLimitRequest loginRequest(final String clientIp) {
        return CheckLimitRequest.newBuilder()
            .setPolicy(Policy.POLICY_LOGIN).setClientIp(clientIp).build();
    }

    private static ReportLoginFailureRequest reportRequest(final String clientIp) {
        return ReportLoginFailureRequest.newBuilder()
            .setSubject(SUBJECT).setClientIp(clientIp).build();
    }

    private static DecisionOutcome allowed() {
        return new DecisionOutcome(
            RateLimitDecision.allow(REMAINING), PrometheusMetricsCollector.RESULT_ALLOWED, null);
    }

    private static DecisionOutcome denied() {
        return new DecisionOutcome(
            RateLimitDecision.deny(RETRY_MS), PrometheusMetricsCollector.RESULT_DENIED, null);
    }

    @Test
    void allowMapsToDecisionAllowWithRemaining() {
        when(engine.decide(any(), any())).thenReturn(allowed());

        service.checkLimit(request(Policy.POLICY_TRANSACTION, ""), obs);

        final CheckLimitResponse resp = obs.next;
        assertNotNull(resp);
        assertEquals(Decision.DECISION_ALLOW, resp.getDecision());
        assertEquals(REMAINING, resp.getRemaining());
        assertFalse(resp.getFailOpen());
        assertEquals(CONFIG_KEY, resp.getPolicyApplied());
    }

    @Test
    void denyMapsToDecisionDenyWithRetryAfter() {
        when(engine.decide(any(), any())).thenReturn(denied());

        service.checkLimit(request(Policy.POLICY_TRANSACTION, ""), obs);

        assertEquals(Decision.DECISION_DENY, obs.next.getDecision());
        assertEquals(RETRY_MS, obs.next.getRetryAfterMs());
        assertEquals(0L, obs.next.getRemaining());
    }

    @Test
    void idempotencyHitReplaysCachedDecisionWithoutCallingEngine() {
        when(idemCache.lookup(anyString()))
            .thenReturn(new Hit(RateLimitDecision.allow(REMAINING)));

        service.checkLimit(request(Policy.POLICY_TRANSACTION, "idem-1"), obs);

        assertEquals(Decision.DECISION_ALLOW, obs.next.getDecision());
        assertEquals(REMAINING, obs.next.getRemaining());
        assertFalse(obs.next.getFailOpen());
        verify(engine, never()).decide(any(), any());
    }

    @Test
    void concurrentLookupAllowsWithFailOpen() {
        when(idemCache.lookup(anyString())).thenReturn(new Concurrent());

        service.checkLimit(request(Policy.POLICY_TRANSACTION, "idem-1"), obs);

        assertEquals(Decision.DECISION_ALLOW, obs.next.getDecision());
        assertTrue(obs.next.getFailOpen());
        verify(engine, never()).decide(any(), any());
    }

    @Test
    void firstLookupCallsEngineAndStores() {
        when(idemCache.lookup(anyString())).thenReturn(new First());
        when(engine.decide(any(), any())).thenReturn(allowed());

        service.checkLimit(request(Policy.POLICY_TRANSACTION, "idem-1"), obs);

        assertEquals(Decision.DECISION_ALLOW, obs.next.getDecision());
        verify(engine).decide(any(), any());
        verify(idemCache).store(anyString(), eq(RateLimitDecision.allow(REMAINING)));
    }

    @Test
    void bypassLookupCallsEngineButDoesNotStore() {
        when(idemCache.lookup(anyString())).thenReturn(new Bypass());
        when(engine.decide(any(), any())).thenReturn(allowed());

        service.checkLimit(request(Policy.POLICY_TRANSACTION, "idem-1"), obs);

        assertEquals(Decision.DECISION_ALLOW, obs.next.getDecision());
        verify(engine).decide(any(), any());
        verify(idemCache, never()).store(anyString(), any());
    }

    @Test
    void failOpenLabelPropagatesToResponse() {
        when(engine.decide(any(), any())).thenReturn(new DecisionOutcome(
            RateLimitDecision.allow(0L),
            PrometheusMetricsCollector.RESULT_FAILOPEN,
            PrometheusMetricsCollector.REASON_REDIS_ERROR));

        service.checkLimit(request(Policy.POLICY_TRANSACTION, ""), obs);

        assertEquals(Decision.DECISION_ALLOW, obs.next.getDecision());
        assertTrue(obs.next.getFailOpen());
    }

    @Test
    void loginAllowMapsToDecisionAllow() {
        when(loginThrottle.check(eq(CLIENT_IP), any()))
            .thenReturn(new LoginCheck(RateLimitDecision.allow(LOGIN_REMAINING), false));

        service.checkLimit(loginRequest(CLIENT_IP), obs);

        assertEquals(Decision.DECISION_ALLOW, obs.next.getDecision());
        assertEquals(LOGIN_REMAINING, obs.next.getRemaining());
        assertFalse(obs.next.getFailOpen());
        assertEquals(LOGIN_KEY, obs.next.getPolicyApplied());
    }

    @Test
    void loginDenyMapsToDecisionDenyWithRetryAfter() {
        when(loginThrottle.check(eq(CLIENT_IP), any()))
            .thenReturn(new LoginCheck(RateLimitDecision.deny(LOGIN_RESET_MS), false));

        service.checkLimit(loginRequest(CLIENT_IP), obs);

        assertEquals(Decision.DECISION_DENY, obs.next.getDecision());
        assertEquals(LOGIN_RESET_MS, obs.next.getRetryAfterMs());
    }

    @Test
    void loginBlankClientIpFailsInvalidArgument() {
        service.checkLimit(loginRequest(""), obs);

        assertNull(obs.next);
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCode(obs.error));
    }

    @Test
    void loginFailOpenPropagatesToResponse() {
        when(loginThrottle.check(eq(CLIENT_IP), any()))
            .thenReturn(new LoginCheck(RateLimitDecision.allow(0L), true));

        service.checkLimit(loginRequest(CLIENT_IP), obs);

        assertEquals(Decision.DECISION_ALLOW, obs.next.getDecision());
        assertTrue(obs.next.getFailOpen());
    }

    @Test
    void reportLoginFailureReturnsFailuresInWindow() {
        when(loginThrottle.recordFailure(eq(CLIENT_IP), any())).thenReturn(FAILURES_IN_WINDOW);
        final TestStreamObserver<ReportLoginFailureResponse> reportObs = new TestStreamObserver<>();

        service.reportLoginFailure(reportRequest(CLIENT_IP), reportObs);

        assertNotNull(reportObs.next);
        assertEquals(FAILURES_IN_WINDOW, reportObs.next.getFailuresInWindow());
        assertTrue(reportObs.completed);
    }

    @Test
    void reportLoginFailureBlankClientIpFailsInvalidArgument() {
        final TestStreamObserver<ReportLoginFailureResponse> reportObs = new TestStreamObserver<>();

        service.reportLoginFailure(reportRequest(""), reportObs);

        assertNull(reportObs.next);
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCode(reportObs.error));
    }

    @Test
    void unrecognizedPolicyFailsInvalidArgument() {
        when(registry.resolve(Policy.UNRECOGNIZED)).thenReturn(Optional.empty());
        final CheckLimitRequest req = CheckLimitRequest.newBuilder()
            .setPolicyValue(UNKNOWN_POLICY_VALUE).setSubject(SUBJECT).build();

        service.checkLimit(req, obs);

        assertNull(obs.next);
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCode(obs.error));
    }

    @Test
    void blankSubjectFailsInvalidArgument() {
        final CheckLimitRequest req = CheckLimitRequest.newBuilder()
            .setPolicy(Policy.POLICY_TRANSACTION).setSubject("").build();

        service.checkLimit(req, obs);

        assertNull(obs.next);
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCode(obs.error));
    }

    private static Status.Code statusCode(final Throwable error) {
        assertNotNull(error);
        return Status.fromThrowable((StatusRuntimeException) error).getCode();
    }

    /**
     * Minimal {@link StreamObserver} that records the first {@code onNext} value and
     * any {@code onError} throwable for assertions.
     *
     * @param <T> response message type
     */
    private static final class TestStreamObserver<T> implements StreamObserver<T> {
        private T next;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(final T value) {
            this.next = value;
        }

        @Override
        public void onError(final Throwable t) {
            this.error = t;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }
}
