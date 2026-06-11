package com.fluxguard.grpc;

import com.fluxguard.engine.DecisionOutcome;
import com.fluxguard.engine.RateLimitEngine;
import com.fluxguard.grpc.IdempotencyCache.Bypass;
import com.fluxguard.grpc.IdempotencyCache.Concurrent;
import com.fluxguard.grpc.IdempotencyCache.First;
import com.fluxguard.grpc.IdempotencyCache.Hit;
import com.fluxguard.grpc.IdempotencyCache.IdemLookup;
import com.fluxguard.grpc.LoginThrottle.LoginCheck;
import com.fluxguard.grpc.PolicyRegistry.ResolvedPolicy;
import com.fluxguard.grpc.ratelimit.v1.CheckLimitRequest;
import com.fluxguard.grpc.ratelimit.v1.CheckLimitResponse;
import com.fluxguard.grpc.ratelimit.v1.Decision;
import com.fluxguard.grpc.ratelimit.v1.Policy;
import com.fluxguard.grpc.ratelimit.v1.RateLimitGrpc;
import com.fluxguard.grpc.ratelimit.v1.ReportLoginFailureRequest;
import com.fluxguard.grpc.ratelimit.v1.ReportLoginFailureResponse;
import com.fluxguard.metrics.PrometheusMetricsCollector;
import com.fluxguard.model.ClientIdentity;
import com.fluxguard.model.RateLimitDecision;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC service implementing the transaction rate-limit policy (Phase 1).
 *
 * <p>{@code CheckLimit} resolves the requested {@link Policy} to a {@link LimitConfig}
 * via {@link PolicyRegistry}, optionally consults the {@link IdempotencyCache} when an
 * idempotency key is supplied, and delegates the decision to {@link RateLimitEngine}.
 *
 * <p>The {@code LOGIN} policy and {@code ReportLoginFailure} implement outcome-aware
 * sliding-window throttling via {@link LoginThrottle}: {@code CheckLimit(LOGIN)} peeks
 * at the window without consuming a slot, and {@code ReportLoginFailure} increments the
 * window only after an actual login failure. Both key on {@code client_ip} (the
 * {@code subject} is audit-only) and fail open so Redis outages never block sign-in.
 */
@Component
public class RateLimitGrpcService extends RateLimitGrpc.RateLimitImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitGrpcService.class);

    /** Prefix for idempotency keys: {@code rl:idem:{subject}:{configKey}:{idem}}. */
    private static final String IDEM_KEY_PREFIX = "rl:idem:";

    private final PolicyRegistry registry;
    private final RateLimitEngine engine;
    private final IdempotencyCache idemCache;
    private final LoginThrottle loginThrottle;

    /**
     * Constructs the service with its collaborators.
     *
     * @param registry      resolves policies to limit configurations
     * @param engine        makes the underlying rate-limit decision
     * @param idemCache     replays decisions for repeated idempotency keys
     * @param loginThrottle outcome-aware sliding-window throttle for the LOGIN policy
     */
    public RateLimitGrpcService(
            final PolicyRegistry registry,
            final RateLimitEngine engine,
            final IdempotencyCache idemCache,
            final LoginThrottle loginThrottle) {
        this.registry = registry;
        this.engine = engine;
        this.idemCache = idemCache;
        this.loginThrottle = loginThrottle;
    }

    /**
     * Evaluates a rate-limit decision for the requested policy and subject.
     *
     * @param req the check-limit request
     * @param obs stream observer that receives the response or an error
     */
    @Override
    public void checkLimit(
            final CheckLimitRequest req, final StreamObserver<CheckLimitResponse> obs) {
        try {
            if (req.getPolicy() == Policy.POLICY_LOGIN) {
                checkLogin(req, obs);
            } else {
                checkSubjectPolicy(req, obs);
            }
        } catch (RuntimeException ex) {
            // Policy/config resolution reads Redis outside the engine's circuit breaker;
            // a Redis failure here must fail open rather than surface as an RPC error.
            LOG.warn("Config lookup failed for policy={} — failing open: {}",
                req.getPolicy(), ex.getMessage());
            respond(obs, RateLimitDecision.allow(0L), true, "");
        }
    }

    private void checkLogin(
            final CheckLimitRequest req, final StreamObserver<CheckLimitResponse> obs) {
        final String clientIp = req.getClientIp();
        if (clientIp.isBlank()) {
            obs.onError(Status.INVALID_ARGUMENT
                .withDescription("client_ip required").asRuntimeException());
            return;
        }
        final ResolvedPolicy rp = registry.resolve(Policy.POLICY_LOGIN).orElseThrow();
        final LoginCheck lc = loginThrottle.check(clientIp, rp.config());
        respond(obs, lc.decision(), lc.failOpen(), rp.configKey());
    }

    private void checkSubjectPolicy(
            final CheckLimitRequest req, final StreamObserver<CheckLimitResponse> obs) {
        final String subject = req.getSubject();
        if (subject.isBlank()) {
            obs.onError(Status.INVALID_ARGUMENT
                .withDescription("subject required").asRuntimeException());
            return;
        }
        resolveAndDispatch(req, subject, obs);
    }

    private void resolveAndDispatch(
            final CheckLimitRequest req,
            final String subject,
            final StreamObserver<CheckLimitResponse> obs) {
        final Optional<ResolvedPolicy> rp = registry.resolve(req.getPolicy());
        if (rp.isEmpty()) {
            obs.onError(Status.INVALID_ARGUMENT
                .withDescription("unknown policy").asRuntimeException());
            return;
        }
        dispatch(req, rp.get(), subject, obs);
    }

    private void dispatch(
            final CheckLimitRequest req,
            final ResolvedPolicy rp,
            final String subject,
            final StreamObserver<CheckLimitResponse> obs) {
        final ClientIdentity id = ClientIdentity.of(subject, rp.configKey());
        final String idem = req.getIdempotencyKey();
        if (idem.isBlank()) {
            respondFromEngine(rp, id, obs);
            return;
        }
        final String key = IDEM_KEY_PREFIX + subject + ":" + rp.configKey() + ":" + idem;
        applyIdempotent(rp, id, key, obs);
    }

    private void applyIdempotent(
            final ResolvedPolicy rp,
            final ClientIdentity id,
            final String key,
            final StreamObserver<CheckLimitResponse> obs) {
        final IdemLookup lookup = idemCache.lookup(key);
        if (lookup instanceof Hit hit) {
            respond(obs, hit.decision(), false, rp.configKey());
        } else if (lookup instanceof Concurrent) {
            respond(obs, RateLimitDecision.allow(0L), true, rp.configKey());
        } else if (lookup instanceof First) {
            final DecisionOutcome outcome = engine.decide(rp.config(), id);
            idemCache.store(key, outcome.decision());
            respondFromOutcome(obs, outcome, rp.configKey());
        } else if (lookup instanceof Bypass) {
            respondFromEngine(rp, id, obs);
        }
    }

    private void respondFromEngine(
            final ResolvedPolicy rp,
            final ClientIdentity id,
            final StreamObserver<CheckLimitResponse> obs) {
        respondFromOutcome(obs, engine.decide(rp.config(), id), rp.configKey());
    }

    private void respondFromOutcome(
            final StreamObserver<CheckLimitResponse> obs,
            final DecisionOutcome outcome,
            final String policyApplied) {
        final boolean failOpen =
            PrometheusMetricsCollector.RESULT_FAILOPEN.equals(outcome.resultLabel());
        respond(obs, outcome.decision(), failOpen, policyApplied);
    }

    private void respond(
            final StreamObserver<CheckLimitResponse> obs,
            final RateLimitDecision decision,
            final boolean failOpen,
            final String policyApplied) {
        obs.onNext(buildResponse(decision, failOpen, policyApplied));
        obs.onCompleted();
    }

    private static CheckLimitResponse buildResponse(
            final RateLimitDecision decision,
            final boolean failOpen,
            final String policyApplied) {
        return CheckLimitResponse.newBuilder()
            .setDecision(decision.allowed() ? Decision.DECISION_ALLOW : Decision.DECISION_DENY)
            .setRemaining(decision.remainingTokens())
            .setRetryAfterMs(decision.resetAfterMs())
            .setFailOpen(failOpen)
            .setPolicyApplied(policyApplied)
            .build();
    }

    /**
     * Records a failed login for the client IP, advancing its sliding window.
     *
     * <p>The {@code subject} is audit-only and is never keyed on. On any Redis
     * error this still responds with {@code failures_in_window = -1} rather than
     * surfacing an RPC error, keeping failure reporting best-effort.
     *
     * @param req the report-login-failure request
     * @param obs stream observer that receives the failure count
     */
    @Override
    public void reportLoginFailure(
            final ReportLoginFailureRequest req,
            final StreamObserver<ReportLoginFailureResponse> obs) {
        final String clientIp = req.getClientIp();
        if (clientIp.isBlank()) {
            obs.onError(Status.INVALID_ARGUMENT
                .withDescription("client_ip required").asRuntimeException());
            return;
        }
        LOG.debug("LOGIN failure reported: client_ip={} subject={}",
            clientIp, req.getSubject());
        respondFailureCount(obs, recordLoginFailure(clientIp));
    }

    private long recordLoginFailure(final String clientIp) {
        try {
            final ResolvedPolicy rp = registry.resolve(Policy.POLICY_LOGIN).orElseThrow();
            return loginThrottle.recordFailure(clientIp, rp.config());
        } catch (RuntimeException ex) {
            LOG.warn("LOGIN failure record failed for client_ip={}: {}",
                clientIp, ex.getMessage());
            return -1L;
        }
    }

    private static void respondFailureCount(
            final StreamObserver<ReportLoginFailureResponse> obs, final long failures) {
        obs.onNext(ReportLoginFailureResponse.newBuilder()
            .setFailuresInWindow(failures).build());
        obs.onCompleted();
    }
}
