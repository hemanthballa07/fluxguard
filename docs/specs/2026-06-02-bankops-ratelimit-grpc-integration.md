# Bankops ↔ fluxguard Rate-Limit gRPC Integration — Design & Spec

**Date:** 2026-06-02
**Status:** Design approved (autonomous ownership); implementation in progress
**Owner repo:** fluxguard (this repo). Counterpart: bankops-portal.
**Trifecta leg:** 3rd leg — rate limiter in front of bankops, mirroring fluxa's fraud-eval integration.

---

## 1. Context & goal

bankops already calls fluxa's fraud-eval over gRPC (`FluxaFraudClient` → `fluxa.fraud.v1.FraudEval` on `:9095`) before committing a transaction. This spec adds fluxguard as a **synchronous gRPC rate-limit check** that bankops calls **before** fraud-eval, so a rate-limited request fails fast with `429` before spending a fraud call. The integration must mirror the fraud pattern: fail-open, OTel spans into the **shared Jaeger**, sealed-outcome mapping on the bankops side.

Today fluxguard is an HTTP-filter-only rate limiter (Spring Boot 3.4.1 servlet, Java 17) over a token-bucket / sliding-window + Redis-Lua + Resilience4j fail-open core. There is **no gRPC/proto** yet. This spec is the contract bankops is waiting on, plus the fluxguard-side design.

Source bridge messages: `trifecta` channel msg 35 (fluxa kickoff) and msg 36 (bankops answers Q1–Q3 + design heads-ups).

---

## 2. Decisions (resolved forks)

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| D1 | Integration shape | (a) synchronous gRPC pre-check, ordered **before** fraud-eval | bankops Q1; trifecta symmetry with `FluxaFraudClient`; bankops keeps the commit path |
| D2 | gRPC server in Spring Boot | **Raw grpc-java** (`grpc-netty-shaded`) + managed `SmartLifecycle` server bean + `protobuf-maven-plugin` | matches house style ("explicit beans, no magic, no Lombok"); mirrors fluxa's manual server; minimal deps; explicit OTel interceptor |
| D3 | Login brute-force protection | **Outcome-aware** (Option 1): `CheckLimit(LOGIN)` is read-only; `ReportLoginFailure` increments a failures-only sliding window | only option that actually protects login (bankops flagged IP-counting-all as weak); bankops offered to report failures |
| D4 | LOGIN window key | **`client_ip` only**, never username | username-keyed = account-lockout DoS (attacker locks out a victim). Failures-only counting means legit hydration (which succeeds) never accrues, so IP-keying is safe here |
| D5 | Idempotency dedupe | **Atomic reserve** (`SET NX` pending marker) + cache decision; cache ALLOW long TTL, cache DENY for `retry_after_ms` only | bankops nit: a retried Idempotency-Key is one logical op; must not double-spend a token nor wrongly `429` honest retries; DENY short-TTL lets a refilled retry succeed |
| D6 | Phasing | Proto designed in full now; **Phase 1** = TRANSACTION + OPS_* (token bucket), **Phase 2** = LOGIN (outcome-aware) | bankops generates the full client once; the clean transaction MVP ships first, login isn't dropped |
| D7 | HTTP filter | **Kept** (additive) | fluxguard's standalone product feature; gRPC reuses the decision core, not replaces the filter |

---

## 3. Proto contract — `fluxguard.ratelimit.v1`

File: `src/main/proto/fluxguard/ratelimit/v1/ratelimit.proto`

```protobuf
syntax = "proto3";
package fluxguard.ratelimit.v1;

option java_multiple_files = true;
option java_package = "com.fluxguard.grpc.ratelimit.v1";
option java_outer_classname = "RateLimitProto";

// fluxguard's synchronous quota-enforcement surface.
service RateLimit {
  // Called pre-commit (transactions) or pre-handle (login). Fail-open: on any
  // backend failure the server returns DECISION_ALLOW with fail_open=true.
  rpc CheckLimit(CheckLimitRequest) returns (CheckLimitResponse);

  // Records a failed auth so the LOGIN window counts failures, not hydration
  // traffic. Fired by bankops after a 401. Best-effort; fail-open.
  rpc ReportLoginFailure(ReportLoginFailureRequest) returns (ReportLoginFailureResponse);
}

// Decouples fluxguard from bankops URL structure — the {accountId} in the path
// is NOT the bucket key; the policy names the configured limit.
enum Policy {
  POLICY_UNSPECIFIED = 0;
  POLICY_TRANSACTION = 1;  // POST /api/accounts/{id}/transactions          (token bucket)
  POLICY_OPS_RELEASE = 2;  // POST .../transactions/{txnId}/release         (token bucket)
  POLICY_OPS_REJECT  = 3;  // POST .../transactions/{txnId}/reject          (token bucket)
  POLICY_LOGIN       = 4;  // GET  /api/whoami credential check             (sliding window, failures only)
}

enum Decision {
  DECISION_UNSPECIFIED = 0;
  DECISION_ALLOW = 1;
  DECISION_DENY = 2;
}

message CheckLimitRequest {
  string request_id      = 1;  // caller correlation id (OTel/log join)
  Policy policy          = 2;
  string subject         = 3;  // per-user key = Principal.getName() (TRANSACTION/OPS_*)
  string client_ip       = 4;  // window key for LOGIN; audit otherwise
  string idempotency_key = 5;  // TRANSACTION only: replay returns prior decision, no extra token
}

message CheckLimitResponse {
  Decision decision      = 1;
  int64 remaining        = 2;  // quota left (>=0 on allow)
  int64 retry_after_ms   = 3;  // >0 on deny → bankops 429 Retry-After
  bool  fail_open        = 4;  // true when allowed only because backend failed
  string policy_applied  = 5;  // resolved limit name (observability)
}

message ReportLoginFailureRequest {
  string request_id = 1;
  string subject    = 2;  // attempted username (AUDIT ONLY — never the window key)
  string client_ip  = 3;  // the window key (see D4)
}
message ReportLoginFailureResponse {
  int64 failures_in_window = 1;  // count after recording (observability)
}
```

---

## 4. fluxguard-side architecture

### 4.1 Decision-core extraction (refactor, behavior-preserving)

Extract the decision logic currently inline in `RateLimitFilter` into a shared `RateLimitEngine` so the HTTP filter **and** the gRPC service share one fail-open / circuit-breaker / span path.

- **Shared (`RateLimitEngine`):** given `(LimitConfig, bucketKey)` → run `algorithm.buildLuaKeys/buildLuaArgs → LuaScriptExecutor.execute → parseResult` inside the `redis-rate-limit` circuit breaker; on `RedisUnavailableException` / `CallNotPermittedException` return `allow(0)` with a fail-open reason; emit the `rate_limit.decision` span + metrics. Returns a `DecisionOutcome { RateLimitDecision decision; String reason; boolean failOpen; }`.
- **Filter-only (stays in `RateLimitFilter`):** feature flags, dark-launch shadow, `X-Client-ID` extraction, HTTP `429`/`Retry-After`/`X-RateLimit-Remaining` headers. The gRPC path does NOT get feature flags / dark launch in v1.
- **Safety net:** the extraction must keep all 37 `RateLimitFilterTest` + 4 IT green with no behavior change *before* gRPC is added. The circuit breaker instance is shared across both surfaces (Redis is down for both — desired).

### 4.2 Policy registry

`Policy` enum → config key → `LimitConfig`. Reuse the existing `ConfigService` (Redis-backed, admin-tunable) by registering policy configs under stable keys:

| Policy | Config key | Algorithm | Default (tunable via `/admin/configs`) |
|--------|-----------|-----------|----------------------------------------|
| TRANSACTION | `policy:transaction` | token bucket | capacity 20, refill 5/s |
| OPS_RELEASE | `policy:ops_release` | token bucket | capacity 10, refill 2/s |
| OPS_REJECT  | `policy:ops_reject`  | token bucket | capacity 10, refill 2/s |
| LOGIN       | `policy:login`       | sliding window (failures) | 5 failures / 300_000 ms |

Bucket key derivation (per D4): `subjectKey = (policy == LOGIN) ? client_ip : subject`; Redis key = `ClientIdentity.of(subjectKey, configKey)` → `rl:{subjectKey}:{configKey}`.

### 4.3 gRPC server

- `GrpcServer` bean implements `SmartLifecycle`: builds a `grpc-netty-shaded` `Server` on **`:9099`**, registers `RateLimitGrpcService`, the gRPC **health** service (`grpc.health.v1.Health`) and **reflection**, attaches the OTel server interceptor (§7), and on `stop()` calls `server.shutdown().awaitTermination(<grace>)` to drain in-flight checks.
- `RateLimitGrpcService extends RateLimitGrpcImplBase`: maps request → policy config → (idempotency for TRANSACTION) → `RateLimitEngine` → `CheckLimitResponse`. Unknown/`POLICY_UNSPECIFIED` → `INVALID_ARGUMENT`. Internally it never throws on Redis failure — the engine fails open and sets `fail_open=true`.

---

## 5. Algorithms & Redis/Lua

- **Token bucket (TRANSACTION, OPS_*)** — reuse existing `token_bucket.lua` (atomic refill + try-consume). `CheckLimit` = one atomic consume. No change.
- **LOGIN sliding window (H1 — the existing script conflates check+increment).** The existing `sliding_window.lua` does `GET prev + INCR curr` every call. LOGIN needs a **read/write split**:
  - `sliding_window_peek.lua` — read-only weighted count of failures in the window; `CheckLimit(LOGIN)` denies when count ≥ limit. **No increment.**
  - `sliding_window_incr.lua` — increment-only; `ReportLoginFailure` bumps the current window bucket + EXPIRE. (This is the existing increment half, extracted.)
- **Idempotency dedupe (D5)** — Redis, via `LuaScriptExecutor` (architectural rule: no raw Redis):
  - Key: `rl:idem:{subject}:{configKey}:{idempotency_key}`.
  - `idem_reserve_or_get.lua`: if key exists with a cached decision → return it (replay, no token spent); if absent → `SET NX` a `pending` marker (TTL = short, e.g. 5 s) and signal "first-seen, run the bucket"; if a `pending` marker exists but no decision yet (concurrent dupe) → return a sentinel so the service fails **open** (allow) rather than blocking.
  - After the bucket runs, `idem_store.lua` writes the decision: ALLOW → TTL 300 s; DENY → TTL = `retry_after_ms` (so a refilled retry can succeed). Empty `idempotency_key` ⇒ skip dedupe, run the bucket normally (log/metric it).

All idempotency/peek/incr Redis ops are fail-open: any error ⇒ proceed as if no dedupe / allow.

---

## 6. Fail-open semantics & deadlines

- **Server-side (fluxguard):** Redis down / circuit open / idempotency error ⇒ `DECISION_ALLOW`, `fail_open=true`, distinct metric + span attr. Never a gRPC error for a backend failure.
- **Client-side (bankops):** must set a tight gRPC **deadline (~100–150 ms)** — fluxguard's own Redis ceiling is 50 ms — and **fail open** on `DEADLINE_EXCEEDED`/`UNAVAILABLE`/any status, mirroring `FluxaFraudClient` (`FluxguardProperties` fail-open policy). A hung fluxguard must never block a commit.
- **ReportLoginFailure failure** ⇒ bankops swallows (best-effort); an uncounted failure is the accepted cost of the fail-open posture.

---

## 7. Observability

- **Tracing:** attach `GrpcTelemetry.create(openTelemetry).newServerInterceptor()` (from `opentelemetry-grpc-1.6`, version-managed by the existing OTel instrumentation BOM 2.25.0) to the gRPC `ServerBuilder`. This auto-extracts the W3C `traceparent` from gRPC metadata — the symmetric twin of bankops's `GrpcTelemetry` **client** interceptor. The `rate_limit.decision` + `redis.lua_script` spans become children of the extracted server span. Confirm fluxguard's propagator is W3C tracecontext (Spring OTel starter default).
- **Shared Jaeger (H6 — cross-repo, the demo-breaker):** fluxguard MUST export to the **same** Jaeger the trifecta already shares (fluxa/bankops), not a private one. Local default `OTEL_EXPORTER_OTLP_ENDPOINT` must point at the shared collector (`:4317` OTLP/gRPC). Confirm reachable network (shared compose network or host port). Without this, bankops→fluxguard spans land in a different backend and the "one trace across three services" story silently fails.
- **Metrics:** add gRPC request counters/latency + a `rate_limit.fail_open` counter tagged by reason and surface (`grpc` vs `http`).

---

## 8. Ports

| Concern | Port | Notes |
|---------|------|-------|
| fluxguard gRPC server | **:9099** | NEW. Free (outside reserved 9095/9097/16686/4317/4318/8080/4200); adjacent to fluxa's 9095/9097 gRPC block |
| fluxguard HTTP (existing) | :8080 | unchanged |
| Redis | 6379 | unchanged, 50 ms timeout |
| Shared Jaeger OTLP | :4317 | export target (see H6) |

bankops side: `:8080` (HTTP `/api`), `:4200` (Angular). bankops runs no gRPC server, no Redis.

---

## 9. bankops-side wiring (what bankops builds)

1. **`FluxguardClient` + `FluxguardProperties`** mirroring `FluxaFraudClient`: a new `ManagedChannel` to `fluxguard:9099`, the **same `GrpcTelemetry` client interceptor**, a ~100–150 ms deadline, and a sealed fail-open outcome mapping.
2. **Ordering:** call `CheckLimit(TRANSACTION|OPS_*)` **before** `FluxaFraudClient` in the transaction path; `DECISION_DENY` → `429` + `Retry-After` (from `retry_after_ms`) and skip fraud-eval.
3. **Keys:** `subject = Principal.getName()` (HTTP Basic). Pass the request's `Idempotency-Key` as `idempotency_key`.
4. **`client_ip` (H4):** derive from the real socket peer or a trusted proxy — NOT a spoofable `X-Forwarded-For` — or LOGIN protection is bypassable.
5. **Phase 2 login:** before handling `GET /api/whoami`, call `CheckLimit(LOGIN, client_ip)`; on a 401 outcome, call `ReportLoginFailure(client_ip, subject)` via an `AuthenticationFailureHandler` (or the 401 site). Both off the transaction commit path.

---

## 10. Phasing

- **Phase 1 (now):** proto + codegen; `RateLimitEngine` extraction; gRPC server (:9099) + health/reflection + OTel; `CheckLimit` for TRANSACTION/OPS_* with idempotency dedupe; unit + Testcontainers IT; k6 gRPC burst (drain→429→recover); ADR-007; docs. Ship → bankops wires the transaction path.
- **Phase 2 (after):** `sliding_window_peek/incr` split; `CheckLimit(LOGIN)` + `ReportLoginFailure`; IT + k6 brute-force scenario; ADR-008. bankops wires `/whoami` + failure reporting.

---

## 11. Open items / values to confirm

- Limit defaults in §4.2 are sane starting values, admin-tunable via `/admin/configs`; revisit after k6.
- Graceful-shutdown drain timeout (propose 5 s).
- Confirm shared-Jaeger endpoint/network with the trifecta compose (H6) before the cross-service trace demo.

---

## 12. Testing & Definition of Done

- **Unit:** `RateLimitEngine` (fail-open branches), `RateLimitGrpcService` (policy mapping, idempotency replay/concurrent-dupe, INVALID_ARGUMENT), LOGIN peek/incr — mock executor, no Redis.
- **Integration (Testcontainers, real Redis, per house rule — no mocked Redis in IT):** gRPC server up; CheckLimit allow/deny/refill; idempotency replay returns cached decision without extra spend; fail-open on Redis stop; (Phase 2) brute-force window trips and decays.
- **Stress (k6 gRPC):** burst drains a token bucket → `429` → recovers after refill; capture results in `/k6/results/`.
- **DoD:** `mvn verify` green (unit + IT); Checkstyle clean; ADR-007 (+008) written and linked from README; CHANGELOG updated; k6 results captured; cross-service trace (bankops→fluxguard) verified in the shared Jaeger.
