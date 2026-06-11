package com.fluxguard.grpc;

import com.fluxguard.config.ConfigService;
import com.fluxguard.config.LimitConfig;
import com.fluxguard.grpc.ratelimit.v1.CheckLimitRequest;
import com.fluxguard.grpc.ratelimit.v1.CheckLimitResponse;
import com.fluxguard.grpc.ratelimit.v1.Decision;
import com.fluxguard.grpc.ratelimit.v1.Policy;
import com.fluxguard.grpc.ratelimit.v1.RateLimitGrpc;
import com.fluxguard.grpc.ratelimit.v1.ReportLoginFailureRequest;
import com.fluxguard.grpc.ratelimit.v1.ReportLoginFailureResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the gRPC rate-limit server.
 *
 * <p>Boots the full Spring context with the {@link GrpcServer} bound to a fixed
 * test port and a real Redis instance supplied by Testcontainers, then drives the
 * service through a real {@link RateLimitGrpc} blocking client. A tight token
 * bucket (capacity 3, refill 1/s) is seeded for the transaction policy and a tight
 * LOGIN sliding window (limit 3) for outcome-aware login throttling, so allow/deny
 * boundaries are reached in a handful of calls.
 *
 * <p>Tests are ordered: the fail-open test runs last because it stops the Redis
 * container, which would break the remaining cases. The LOGIN cases therefore run
 * before it.
 */
@SpringBootTest
@TestPropertySource(properties = "fluxguard.grpc.port=19099")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class RateLimitGrpcIT {

    private static final int REDIS_PORT = 6379;

    /** Fixed gRPC port the test client connects to. */
    private static final int GRPC_PORT = 19099;

    /** Token-bucket capacity seeded for the transaction policy. */
    private static final int CAPACITY = 3;

    /** Token-bucket refill rate (tokens/sec) seeded for the transaction policy. */
    private static final int REFILL_PER_SEC = 1;

    /** Config key the transaction policy resolves to in {@code PolicyRegistry}. */
    private static final String CONFIG_KEY = "policy:transaction";

    /** Sleep long enough for the 1/s bucket to refill one token. */
    private static final long REFILL_SLEEP_MS = 1_100L;

    /** Config key the LOGIN policy resolves to in {@code PolicyRegistry}. */
    private static final String LOGIN_CONFIG_KEY = "policy:login";

    /** Tight failure limit seeded for the LOGIN policy so deny is reachable. */
    private static final int LOGIN_LIMIT = 3;

    /** LOGIN failure window (ms); large so the window does not roll mid-test. */
    private static final long LOGIN_WINDOW_MS = 300_000L;

    private static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    /**
     * Wires the Testcontainers Redis host/port into the Spring context.
     *
     * @param reg dynamic property registry provided by Spring Test
     */
    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry reg) {
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired
    private ConfigService configService;

    private ManagedChannel channel;
    private RateLimitGrpc.RateLimitBlockingStub stub;

    /**
     * Seeds a tight limit and opens a plaintext blocking client to the server.
     */
    @BeforeAll
    void setUp() {
        configService.putConfig(CONFIG_KEY,
            LimitConfig.tokenBucket(CONFIG_KEY, CAPACITY, REFILL_PER_SEC));
        configService.putConfig(LOGIN_CONFIG_KEY,
            LimitConfig.slidingWindow(LOGIN_CONFIG_KEY, LOGIN_LIMIT, LOGIN_WINDOW_MS));
        channel = NettyChannelBuilder.forAddress("localhost", GRPC_PORT)
            .usePlaintext()
            .build();
        stub = RateLimitGrpc.newBlockingStub(channel);
    }

    /**
     * Shuts the gRPC client channel down.
     *
     * @throws InterruptedException if interrupted while awaiting shutdown
     */
    @AfterAll
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(GRPC_PORT, TimeUnit.MILLISECONDS);
    }

    /**
     * Capacity calls are allowed with decreasing remaining; the next call is denied.
     */
    @Test
    @Order(1)
    void allowsUpToCapacityThenDenies() {
        final String subject = "user-allow-" + System.nanoTime();
        for (int i = 0; i < CAPACITY; i++) {
            final CheckLimitResponse allow = stub.checkLimit(request(subject, ""));
            assertEquals(Decision.DECISION_ALLOW, allow.getDecision(), "call within capacity");
            assertEquals(CAPACITY - 1 - i, allow.getRemaining(), "remaining decrements");
        }
        final CheckLimitResponse deny = stub.checkLimit(request(subject, ""));
        assertEquals(Decision.DECISION_DENY, deny.getDecision(), "call beyond capacity denied");
        assertTrue(deny.getRetryAfterMs() > 0, "deny carries a positive retry hint");
    }

    /**
     * After a deny, waiting one refill interval lets the next call through.
     *
     * @throws InterruptedException if the refill sleep is interrupted
     */
    @Test
    @Order(2)
    void refillsOneTokenAfterInterval() throws InterruptedException {
        final String subject = "user-refill-" + System.nanoTime();
        for (int i = 0; i < CAPACITY; i++) {
            stub.checkLimit(request(subject, ""));
        }
        final CheckLimitResponse deny = stub.checkLimit(request(subject, ""));
        assertEquals(Decision.DECISION_DENY, deny.getDecision(), "drained bucket denies");
        Thread.sleep(REFILL_SLEEP_MS);
        final CheckLimitResponse refilled = stub.checkLimit(request(subject, ""));
        assertEquals(Decision.DECISION_ALLOW, refilled.getDecision(), "refilled token allows");
    }

    /**
     * Replaying the same idempotency key replays the decision without double-spend.
     */
    @Test
    @Order(3)
    void idempotentReplayDoesNotDoubleSpend() {
        final String subject = "user-idem-" + System.nanoTime();
        final CheckLimitResponse first = stub.checkLimit(request(subject, "k1"));
        assertEquals(Decision.DECISION_ALLOW, first.getDecision(), "first idem call allowed");
        final long remaining = first.getRemaining();

        final CheckLimitResponse replay = stub.checkLimit(request(subject, "k1"));
        assertEquals(Decision.DECISION_ALLOW, replay.getDecision(), "replay allowed");
        assertEquals(remaining, replay.getRemaining(), "replay returns identical remaining");

        final CheckLimitResponse fresh = stub.checkLimit(request(subject, ""));
        assertEquals(Decision.DECISION_ALLOW, fresh.getDecision(), "fresh call allowed");
        assertEquals(remaining - 1, fresh.getRemaining(),
            "bucket only moved once for the replayed key");
    }

    /**
     * A fresh client IP that has not failed any login is allowed under the limit.
     */
    @Test
    @Order(4)
    void loginAllowsUnderFailureLimit() {
        final String clientIp = ip("allow");
        final CheckLimitResponse allow = stub.checkLimit(loginRequest(clientIp));
        assertEquals(Decision.DECISION_ALLOW, allow.getDecision(),
            "fresh IP under the failure limit is allowed");
        assertEquals(LOGIN_CONFIG_KEY, allow.getPolicyApplied(), "LOGIN policy applied");
    }

    /**
     * Reporting failures up to the limit for one IP fills its window, after which
     * a peek for that IP is denied with a positive retry hint.
     */
    @Test
    @Order(5)
    void loginDeniesAfterWindowFills() {
        final String clientIp = ip("deny");
        for (int i = 1; i <= LOGIN_LIMIT; i++) {
            final ReportLoginFailureResponse rep = stub.reportLoginFailure(reportRequest(clientIp));
            assertEquals(i, rep.getFailuresInWindow(), "failures_in_window increments per report");
        }
        final CheckLimitResponse deny = stub.checkLimit(loginRequest(clientIp));
        assertEquals(Decision.DECISION_DENY, deny.getDecision(), "IP over the limit is denied");
        assertTrue(deny.getRetryAfterMs() > 0, "deny carries a positive retry hint");
    }

    /**
     * Failures recorded for one IP do not lock out a different IP — the window is
     * per-IP, so one attacker cannot deny sign-in for everyone else.
     */
    @Test
    @Order(6)
    void loginWindowIsIsolatedPerIp() {
        final String attackerIp = ip("attacker");
        for (int i = 0; i < LOGIN_LIMIT; i++) {
            stub.reportLoginFailure(reportRequest(attackerIp));
        }
        assertEquals(Decision.DECISION_DENY,
            stub.checkLimit(loginRequest(attackerIp)).getDecision(), "attacker IP is denied");

        final String bystanderIp = ip("bystander");
        assertEquals(Decision.DECISION_ALLOW,
            stub.checkLimit(loginRequest(bystanderIp)).getDecision(),
            "a different fresh IP is still allowed");
    }

    /**
     * A LOGIN check with a blank client_ip is rejected with INVALID_ARGUMENT.
     */
    @Test
    @Order(7)
    void loginBlankClientIpFailsInvalidArgument() {
        final StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
            () -> stub.checkLimit(loginRequest("")), "blank client_ip is rejected");
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(ex).getCode(),
            "blank client_ip maps to INVALID_ARGUMENT");
    }

    /**
     * With Redis stopped the server fails open: the call is allowed and flagged.
     */
    @Test
    @Order(8)
    void failsOpenWhenRedisDown() {
        REDIS.stop();
        final CheckLimitResponse resp =
            stub.checkLimit(request("user-failopen-" + System.nanoTime(), ""));
        assertEquals(Decision.DECISION_ALLOW, resp.getDecision(), "fail-open allows");
        assertTrue(resp.getFailOpen(), "fail-open flag is set");
    }

    private static CheckLimitRequest request(final String subject, final String idempotencyKey) {
        return CheckLimitRequest.newBuilder()
            .setPolicy(Policy.POLICY_TRANSACTION)
            .setSubject(subject)
            .setIdempotencyKey(idempotencyKey)
            .build();
    }

    private static String ip(final String label) {
        return "10.0." + label + "." + System.nanoTime();
    }

    private static CheckLimitRequest loginRequest(final String clientIp) {
        return CheckLimitRequest.newBuilder()
            .setPolicy(Policy.POLICY_LOGIN)
            .setClientIp(clientIp)
            .build();
    }

    private static ReportLoginFailureRequest reportRequest(final String clientIp) {
        return ReportLoginFailureRequest.newBuilder()
            .setSubject("audit-only")
            .setClientIp(clientIp)
            .build();
    }
}
