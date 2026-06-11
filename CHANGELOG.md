# CHANGELOG.md
> Append-only log of every meaningful change.
> Never delete entries. Newest at top.
> Format: ## [date] — [week] — [what changed]

---

## [2026-06-02] — Trifecta Step 3 — CI hardened against transient Docker Hub outages

The PR CI flaked when the GitHub Actions runner couldn't reach `registry-1.docker.io` (HTTP 500 / connection timeout) while Testcontainers pulled `ryuk` — a transient Docker Hub blip, not a code defect: the same commit `3d8085f` passed hours earlier, and a re-run went green with no code change. Hardened `ci.yml` so brief registry blips no longer fail the build:

- New **"Warm Docker image cache"** step pre-pulls `redis:7-alpine` with 6× retry / 15s backoff before the integration-test phase, isolating all Docker Hub interaction to one retrying step
- `TESTCONTAINERS_RYUK_DISABLED=true` on the integration-test step — the resource reaper is unnecessary on ephemeral CI runners and was the exact image that failed; removing it drops one Docker Hub round-trip

---

## [2026-06-02] — Trifecta Step 3 (Phase 2) — outcome-aware LOGIN rate limiting

Implements the LOGIN side of the gRPC surface (ADR-008): `CheckLimit(LOGIN)` + `ReportLoginFailure`, counting auth **failures** (not hydration traffic) in an IP-keyed sliding window, so brute-force is throttled without throttling normal dual-purpose `/whoami` use.

- `sliding_window.lua` split → `sliding_window_peek.lua` (read-only weighted count) + `sliding_window_incr.lua` (increment-only): `CheckLimit(LOGIN)` peeks (no write), `ReportLoginFailure` increments
- `LoginThrottle` — IP-keyed `check()` (fail-open) + `recordFailure()` (best-effort); window keyed on `client_ip` **only**, never username → no account-lockout DoS
- `RateLimitGrpcService` — LOGIN no longer `UNIMPLEMENTED`; blank `client_ip` → `INVALID_ARGUMENT`; `ReportLoginFailure` returns `failures_in_window`
- Tests: `mvn verify` green — **218 unit + 30 IT** (+10 unit, +4 IT). `RateLimitGrpcIT` LOGIN cases: allow under limit, deny after the window fills (failures 1→2→3), per-IP isolation, blank-IP rejected
- k6 `grpc-login-bruteforce.js` — one attacker IP looping CheckLimit + ReportLoginFailure trips `DECISION_DENY` once the failure window fills

---

## [2026-06-02] — Trifecta Step 3 (Phase 1) — synchronous gRPC rate-limit surface for bankops

Adds a gRPC `CheckLimit` service so bankops-portal can rate-limit transactions pre-commit (before its fraud-eval gRPC call), mirroring the fluxa fraud integration. Design + decisions in `docs/specs/2026-06-02-bankops-ratelimit-grpc-integration.md`; rationale in ADR-007.

### New gRPC surface (`fluxguard.ratelimit.v1`)
- `ratelimit.proto` — `RateLimit` service (`CheckLimit` + `ReportLoginFailure`), `Policy`/`Decision` enums; codegen via `protobuf-maven-plugin` + grpc-java 1.69.0 (`grpc-netty-shaded`, no unshaded Netty); OTel `opentelemetry-grpc-1.6` via the alpha instrumentation BOM
- `GrpcServer` (`SmartLifecycle`, port `:9099`) — health + reflection + `GrpcTelemetry` server interceptor (W3C traceparent → shared Jaeger); graceful drain on shutdown
- `RateLimitGrpcService.CheckLimit` (transactions/ops) — resolves `Policy` → `LimitConfig` via `PolicyRegistry` (admin-tunable, static defaults), delegates to `RateLimitEngine`; fails open (allow, `fail_open=true`) on Redis errors incl. the config-lookup path outside the circuit breaker; `LOGIN` + `ReportLoginFailure` return `UNIMPLEMENTED` (Phase 2)
- `IdempotencyCache` + `idem_reserve_or_get.lua` / `idem_store.lua` — a replayed `Idempotency-Key` returns the prior decision without spending a token (atomic reserve; ALLOW cached 5m, DENY cached for `retry_after_ms`); concurrent dupes and Redis errors fail open

### Refactor
- `RateLimitEngine` extracted from `RateLimitFilter` — shared, transport-agnostic decision core (Redis call + circuit breaker + decision metrics + `rate_limit.decision` span). Behaviour-preserving: filter test assertions unchanged

### Tests & stress
- `mvn verify`: BUILD SUCCESS — 208 unit + 26 IT (was 178 + 22); `RateLimitGrpcIT` (Testcontainers Redis): allow→deny→refill, idempotency replay without double-spend, fail-open on Redis stop
- k6 gRPC burst (`k6/scripts/grpc-transaction-burst.js`): drain→deny→recover proven live — 21 allowed / 379 denied / 10 post-refill allowed, p95 19ms

---

## [2026-04-17] — Hardening + release — Production-hardening, test coverage expansion, null overrideConfig bugfix

### Bugfixes
- `RedisFeatureFlagService.serialize()` — NPE when `overrideConfig == null`; wrapped algorithm block in `if (flag.overrideConfig() != null)` guard
- `RedisFeatureFlagService.deserialize()` — missing `algorithm` field (empty string from `asText()`) previously returned `Optional.empty()`, making flags without overrides invisible after storage; added `else if (algorithm.isEmpty()) { overrideConfig = null; }` branch

### New tests (197 total: 175 unit + 22 IT)
- `RateLimitFilterTest` (+4) — shadow Redis failure swallowed; plain `RuntimeException` fails open + `redis_error` counter; malformed Lua result (`ClassCastException`) fails open; 3 repeated requests accumulate allowed counter correctly
- `RedisAuditServiceTest` (+2) — blank actor normalizes to `"unknown"`; `trim()` failure after `rightPush()` is swallowed
- `AdminControllerTest` (+13) — rollout 0/100 valid; rollout −1 → 400; zero/negative capacity → 400; negative `refillRatePerSecond` → 400; zero limit → 400; negative `windowMs` → 400; audit limit 0/1/1000 pass-through; audit limit 1001 capped at 1000; delete-nonexistent config → 204; delete-nonexistent flag → 204
- `RedisFeatureFlagServiceTest` (+2) — `putFlag` with `null` override serializes without algorithm fields; `getFlagForEndpoint` from JSON with no `algorithm` field deserializes to flag with `null` `overrideConfig`
- `AdminAuthFilterIT` (+4) — config CRUD round-trip (PUT → GET verify → DELETE → GET absent); flag CRUD round-trip with token_bucket override (PUT → GET shape → DELETE → GET absent); `PUT /admin/flags` without override → 200 (regression guard); `GET /admin/audit` returns JSON array

### `mvn verify`: BUILD SUCCESS — 175 unit + 22 IT = 197 total, 0 failures

---

## [2026-04-17] — Week 11 (continued) — /admin/flags CRUD, AdminAuthFilterIT, Jackson serialization fix

- `FeatureFlagRequest.java` (new) — request record for `PUT /admin/flags`: `enabled`, `darkLaunch`, `rolloutPercent`, optional `algorithm` + `capacity`/`refillRatePerSecond`/`limit`/`windowMs` fields
- `AdminController.java` — added `GET/PUT/DELETE /admin/flags`; `rolloutPercent` validated 0–100 in controller; optional `overrideConfig` built with same field validation as `/admin/configs`; all mutations audited; constructor now takes `FeatureFlagService`; `HttpServletRequest` moved to method-level parameter injection (fixes `SlidingWindowIT` which uses `web-application-type=none`)
- `AdminControllerTest.java` — 26 unit tests (was 17); 8 new: `getAllFlags`, `putFlag` no-override, `putFlag` token-bucket override, `putFlag` invalid algo → 400, `putFlag` token-bucket missing capacity → 400, `putFlag` rollout > 100 → 400, `removeFlag` delegates, audit on put + remove
- `AdminAuthFilterIT.java` (new) — 5 integration tests against real Spring + Testcontainers Redis: missing key → 401 on `/admin/configs`, wrong key → 401 on `/admin/configs`, correct key → 200 `/admin/configs` (response body verified: contains `endpointPattern` and `type` discriminator), missing key → 401 on `/admin/flags`, correct key → 200 `/admin/flags`
- `RateLimitFilter.java` — path/config lookup now runs **before** `X-Client-ID` check; paths with no registered config (all `/admin/**`) pass through without 400 regardless of client-ID header presence
- `RateLimitAlgorithm.java` — `@JsonTypeInfo(Id.NAME, property="type")` + `@JsonSubTypes` for `TokenBucketAlgorithm`/`SlidingWindowAlgorithm`; fixes latent HTTP 500 on `GET /admin/configs`
- `TokenBucketAlgorithm.java` — `@JsonProperty` on `capacity()` and `refillRatePerSecond()`
- `SlidingWindowAlgorithm.java` — `@JsonProperty` on `limit()` and `windowMs()`
- `pom.xml` — pinned `opentelemetry-instrumentation-api-incubator` to `2.25.0-alpha` in `dependencyManagement` (fixes conflict introduced by `micrometer-tracing-bridge-otel:1.4.x` pulling `2.9.0-alpha`)
- `mvn test`: BUILD SUCCESS, 154 unit tests, 0 failures
- `mvn verify`: BUILD SUCCESS, 154 unit + 16 IT tests, 0 failures

## [2026-04-17] — Week 11 — Admin auth (API-key header) + append-only audit log

- `AdminAuthFilter.java` (new) — `HandlerInterceptor` for `/admin/**`; validates `X-Admin-Api-Key` header; 401 on miss or mismatch; sets `X-Admin-Actor = "admin"` on success; key injected via constructor (no `@Value` on class — testable without Spring)
- `AuditService.java` (new) — interface: `record(action, target, details, actor)` + `getRecent(count)`
- `RedisAuditService.java` (new) — dual-write: INFO log via `audit.admin` logger (always, even on Redis failure) + `RPUSH fluxguard:audit-log <json>` capped at 10 000 via `LTRIM -10000 -1`; Redis failure is fail-open (WARN log only); `getRecent()` returns `LRANGE(-count, -1)` oldest-first
- `RateLimitConfiguration.java` — added `@Bean AdminAuthFilter` (reads `@Value("${fluxguard.admin.api-key}")`) + `@Bean AuditService`
- `WebMvcConfig.java` — injected `AdminAuthFilter`; registered scoped to `/admin/**` after `RateLimitFilter`
- `AdminController.java` — added `AuditService` + `HttpServletRequest` to constructor; `actor()` helper reads `X-Admin-Actor` request attr; audit call on `putConfig` (success path), `removeConfig`, `activateKillSwitch`, `deactivateKillSwitch`; added `GET /admin/audit?limit=N` (default 100, cap 1000)
- `application.yml` — added `fluxguard.admin.api-key: ${ADMIN_API_KEY:changeme-replace-in-prod}`
- `AdminAuthFilterTest.java` (new) — 4 unit tests: correct key allows + sets actor, wrong key → 401, missing → 401, empty → 401
- `RedisAuditServiceTest.java` (new) — 6 unit tests: rightPush+trim args, null actor → "unknown", Redis fail-open, range delegation, range failure → empty list, null result → empty list
- `AdminControllerTest.java` — 17 unit tests (was 9); `AuditService` + `HttpServletRequest` mocks added; audit verified on all mutations; 2 new `getAuditLog` tests (delegates, caps at 1000); actor propagation test
- `mvn test` — BUILD SUCCESS, 145 unit tests, 0 failures

## [2026-04-17] — Week 12 — Final polish: deploy.sh rewrite, MDC trace_id fix, Week 10 production gap filled

- `scripts/deploy.sh` — complete rewrite: fluxguard ECR repo name, SHA + latest dual-tags, `:?` env guards for required vars (`ECR_REGISTRY`, `AWS_REGION`, `ECS_CLUSTER`, `ECS_SERVICE`), `aws ecs wait services-stable` for rollout verification, optional smoke test via `APP_URL/actuator/health`
- `pom.xml` — added `micrometer-tracing-bridge-otel` (Spring Boot BOM-managed, no explicit version); bridges OTel context into Micrometer, populating `traceId`/`spanId` MDC keys for Logback
- `logback-spring.xml` (new) — `traceId=%X{traceId} spanId=%X{spanId}` pattern in console appender; resolves the known issue of empty log MDC fields
- `src/main/java/com/fluxguard/util/HashUtil.java` (new) — `rolloutBucket(endpoint, clientId)` → deterministic int [0,99] via `Math.abs(Objects.hash(endpoint, clientId)) % 100`
- `src/main/java/com/fluxguard/model/FeatureFlag.java` (new) — record: `endpoint`, `enabled`, `darkLaunch`, `rolloutPercent`, `overrideConfig`
- `src/main/java/com/fluxguard/config/FeatureFlagService.java` (new) — interface: `getFlagForEndpoint`, `isClientInRollout`, `getAllFlags`, `putFlag`, `removeFlag`
- `src/main/java/com/fluxguard/config/RedisFeatureFlagService.java` (new) — Redis hash `fluxguard:flags`; flat JSON per endpoint; token-bucket + sliding-window deserialization; `@Bean` only (no `@Component`)
- `src/main/java/com/fluxguard/filter/RateLimitFilter.java` — wired `FeatureFlagService`; added `applyWithFlag()` (enabled + rollout check → live or dark-launch routing); `runDarkLaunchShadow()` (`:dark`-suffixed bucket, fail-open, records `rate.limit.dark_launch.would_deny`)
- `src/main/java/com/fluxguard/metrics/PrometheusMetricsCollector.java` — added `METRIC_DARK_LAUNCH = "rate.limit.dark_launch.would_deny"` + `recordDarkLaunchWouldDeny(endpoint)` counter
- `src/main/java/com/fluxguard/config/RateLimitConfiguration.java` — added `@Bean FeatureFlagService`
- `src/test/java/com/fluxguard/filter/RateLimitFilterTracingTest.java` — added `stubFlagService()` returning `Optional.empty()`, updated filter constructor call
- Corrected status: Week 11 (auth+audit) was never committed — reset to ⬜; Week 10 production code confirmed present
- `mvn test` — BUILD SUCCESS, 127 unit tests, 0 failures

## [NOT COMMITTED] — Week 11 — Admin auth (API-key header) + append-only audit log
> **Note:** This entry was written in a prior session but the corresponding code was never committed to main. Deferred to next session.
- Planned: `AdminAuthFilter`, `AuditService`, `RedisAuditService`, `ADR-006`

## [2026-04-16] — Week 7 — OTLP gRPC fix + log pattern correction
- `application.yml` — added `protocol: grpc` under `otel.exporter.otlp`; OTel starter requires explicit protocol to use gRPC (port 4317) instead of defaulting to http/protobuf (port 4318)
- `application.yml` — fixed log pattern MDC keys: `%X{traceId}` → `%X{trace_id}`, `%X{spanId}` → `%X{span_id}`; OTel uses snake_case MDC keys, not Micrometer-style camelCase
- Jaeger E2E verification passed: `rate_limit.decision` and `redis.lua_script` spans confirmed for service `fluxguard`
- Known gap logged: log `trace_id=` remains empty — `opentelemetry-logback-mdc-1.0` MDC bridge dep + `logback-spring.xml` needed; deferred to Month 2 cleanup
- Test counts corrected: 90 total (83 unit + 7 IT)

## [2026-04-16] — Week 1 — pom.xml resolved clean
- `pom.xml` re-initialized: Spring Boot 3.4.1 parent, `opentelemetry-instrumentation-bom` 2.25.0 imported explicitly, Resilience4j 2.3.0 pinned, Testcontainers 1.21.4, JaCoCo 0.8.14, Checkstyle 3.6.0
- `checkstyle/checkstyle.xml` scaffolded with the project code-style rules (Javadoc on public methods, max 30-line methods, no magic numbers)
- `mvn dependency:resolve` — BUILD SUCCESS, no conflicts

## [2026-04-16] — Week 7 — OpenTelemetry tracing implemented and verified
- `RateLimitFilter.java` — added parent span `rate_limit.decision` with `SpanKind.INTERNAL`; span is current before `LuaScriptExecutor.execute()`; attributes include `client.id`, `endpoint`, `algorithm`, `decision`, and `rate_limit.remaining` / `rate_limit.reset_after_ms` when applicable; `redis_error` fail-open marks span `ERROR`, `circuit_open` remains `OK`
- `LuaScriptExecutor.java` — added child span `redis.lua_script` with `SpanKind.CLIENT`; attributes include `script_name` and `key_count`; `metrics.recordScriptDuration()` remains before the null check; span ends in `finally`
- `RateLimitConfiguration.java` — added `Tracer` bean backed by auto-configured `OpenTelemetry` so the updated constructors wire in the full Spring context
- `RateLimitFilterTest.java` — updated constructor wiring to pass `OpenTelemetry.noop().getTracer("test")`
- `RateLimitFilterTracingTest.java` (new) — parent span tests covering allow, deny, `redis_error` fail-open, `circuit_open` fail-open, current-span propagation, and no-span paths
- `LuaScriptExecutorTracingTest.java` (new) — child span tests covering attributes, null-result error handling, and parent-child linkage
- Verification:
  - `mvn compile` ✅
  - `mvn test` ✅
  - `mvn verify` ✅
  - Jaeger API query confirmed `rate_limit.decision` → `redis.lua_script` parent-child traces for service `fluxguard` ✅

## [2026-04-15] — Week 6 — Grafana compatibility fix: pinned to 11.4.0
- `docker/docker-compose.yml` — changed `grafana/grafana:latest` to `grafana/grafana:11.4.0`; Grafana 13 (`latest`) uses unified storage mode 5 which routes dashboard reads through a new K8s-style API (`/apis/dashboard.grafana.app/v2/`) and returns 404 on the legacy `/api/dashboards/uid/:uid` endpoint; 11.4.0 uses the standard file provisioner and REST API
- Verified end-to-end: `fg-traffic` → `FluxGuard Traffic Overview`, `fg-internals` → `FluxGuard Limiter Internals`, `fg-perclient` → `FluxGuard Per Client`; datasource proxy returns `"status":"success"` for `rate_limit_allowed_total`

## [2026-04-15] — Week 6 — Grafana dashboards + provisioning
- `grafana/dashboards/traffic-overview.json` — 4 time-series panels: allowed/sec, denied/sec, fail-open/sec, and 429 percentage using `rate_limit_allowed_total`, `rate_limit_denied_total`, and `rate_limit_failopen_total` filtered by `application="fluxguard"`
- `grafana/dashboards/limiter-internals.json` — 4 time-series panels for p50/p95/p99 decision latency from `rate_limit_duration_seconds_bucket` plus Redis script p99 from `redis_script_duration_seconds_bucket`; all Y-axes in seconds
- `grafana/dashboards/per-client.json` — endpoint-grouped allowed/sec and denied/sec panels plus fail-open grouped by reason
- `grafana/provisioning/dashboards/dashboards.yaml` — file-based Grafana dashboard provisioning; provider `fluxguard-dashboards`; folder `FluxGuard`; path `/var/lib/grafana/dashboards`
- `grafana/provisioning/datasources/prometheus.yaml` — default Prometheus datasource pointing to `http://prometheus:9090` with uid `prometheus`
- Dashboard JSON uses schema version 36 and `timeseries` panels only

## [2026-04-15] — Week 5 — Prometheus metrics wired into RateLimitFilter + LuaScriptExecutor
- `PrometheusMetricsCollector.java` (new) — Micrometer facade in `metrics/`; five metric families: `rate.limit.allowed` counter (endpoint, algorithm), `rate.limit.denied` counter (endpoint, algorithm), `rate.limit.failopen` counter (endpoint, reason), `rate.limit.duration` histogram (endpoint, algorithm, result), `redis.script.duration` histogram (script_name); all with `publishPercentileHistogram()` for `_bucket` output; lazy registration via `Counter/Timer.builder().register(registry)`
- `RateLimitFilter.java` — replaced inline `MeterRegistry` + two `Counter` fields with `PrometheusMetricsCollector` injection; added `executeAndApply()` + `recordMetrics()` helpers; `DecisionOutcome` private record carries decision + resultLabel + failOpenReason; duration recorded only when a real rate-limit decision is made (not on 400 or unknown path); 21 unit tests (7 new: allowed/denied counters, duration on allow/deny/failopen, no-duration on 400, no-duration on unknown path)
- `LuaScriptExecutor.java` — injected `PrometheusMetricsCollector`; `redis.script.duration` recorded between `redisTemplate.execute()` call and null-check, capturing every Redis round-trip including null-return failures
- `PrometheusMetricsCollectorTest.java` (new) — 11 pure-Java unit tests; `SimpleMeterRegistry`; verifies counter isolation, tag correctness, timer sample count
- `/actuator/prometheus` verified: all 5 families (`rate_limit_allowed_total`, `rate_limit_denied_total`, `rate_limit_failopen_total`, `rate_limit_duration_seconds_bucket`, `redis_script_duration_seconds_bucket`) present and correct
- `mvn test`: 71 tests (71 unit), 0 failures, 0 Checkstyle violations
- `mvn verify`: 78 tests (71 unit + 7 IT), 0 failures, BUILD SUCCESS

## [2026-04-15] — Month 1 complete — Core Foundation shipped
- Month 1 feature milestones completed
- 60 tests passing (53 unit + 7 IT), 0 Checkstyle violations, BUILD SUCCESS
- Core components: `TokenBucketAlgorithm`, `SlidingWindowAlgorithm`, `token_bucket.lua`, `sliding_window.lua`, `LuaScriptExecutor`, `RateLimitFilter`, Resilience4j circuit breaker
- Infrastructure: multi-stage `Dockerfile`, GitHub Actions CI/CD (test + ECR push)
- All Redis ops atomic via Lua EVAL; fail-open on any Redis exception or circuit open
- Moving to Month 2: Prometheus metrics → Grafana dashboards → OpenTelemetry → k6 benchmarks

## [2026-04-15] — Week 4 — RateLimitFilter + Resilience4j circuit breaker
- `application.yml` — added `resilience4j.circuitbreaker.instances.redis-rate-limit` (TIME_BASED 10s window, 50% threshold, min 5 calls, 10s open wait)
- `RateLimitConfiguration.java` — `@Configuration` providing `Map<String, LimitConfig>` bean (sample limits for `/api/search` + `/api/ingest`) and `CircuitBreaker` bean
- `RateLimitFilter.java` — `HandlerInterceptor`; extracts `X-Client-ID` (400 if missing/blank); exact-path config lookup (skip if unknown); CB-wrapped executor call; `X-RateLimit-Remaining` on allow (guarded: only set when `>= 0`); 429 + `Retry-After` on deny; fail-open on `RuntimeException` (`redis_error` counter) and `CallNotPermittedException` (`circuit_open` counter)
- `WebMvcConfig.java` — `WebMvcConfigurer`; registers `RateLimitFilter` for all paths
- `RateLimitFilterTest.java` — 14 pure-Java unit tests (400/missing-header, unknown-path skip, allow+header, negative-remaining guard, deny+429, Retry-After ceil, Redis fail-open, circuit-open fail-open, counter tag isolation)
- `RateLimitFilterIT.java` — 4 Testcontainers IT tests (exact limit + decrement, limit+1 → 429 + Retry-After, missing header → 400, unknown path → 200)
- `mockito-extensions/org.mockito.plugins.MockMaker` — switched to `mock-maker-subclass` for Java 25 compatibility
- `pom.xml` — added `-XX:+EnableDynamicAgentLoading -Xshare:off` to Surefire argLine for Java 25 + Mockito
- `mvn test`: 53 tests, 0 failures, 0 Checkstyle violations
- `mvn verify`: 60 tests (53 unit + 7 IT), 0 failures, BUILD SUCCESS

## [2026-04-15] — Pre-Week 4 hardening — 6 fixes before RateLimitFilter
- `token_bucket.lua` — added `if elapsed_ms < 0 then elapsed_ms = 0 end` guard after elapsed-ms computation to prevent negative refill on clock skew
- `LuaScriptExecutor.java` — (a) cached `RedisScript` instances in `ConcurrentHashMap<String, RedisScript<List>>` via `computeIfAbsent` to avoid recomputing SHA-1 on every call; (b) null-guarded `redisTemplate.execute()` result, throwing `RedisUnavailableException` on null
- `RedisUnavailableException.java` — new unchecked exception in `exception/`; constructors `(message)` and `(message, cause)`
- `RateLimitException.java` — new unchecked exception in `exception/`; carries `retryAfterMs` field with getter
- `SlidingWindowAlgorithm.java` — removed dead constant `MILLIS_PER_SEC = 1000L` (never referenced); added constructor validation (`limit > 0`, `windowMs > 0`)
- `TokenBucketAlgorithm.java` — added constructor validation (`capacity > 0`, `refillRatePerSecond > 0`)
- `LimitConfig.java` — added `endpointPattern` null/blank validation in both factory methods
- `mvn test`: 39 tests, 0 failures, 0 Checkstyle violations
- `mvn verify`: 42 tests (39 unit + 3 integration), 0 failures, BUILD SUCCESS

## [2026-04-15] — Week 3 — sliding window counter + integration tests
- `SlidingWindowAlgorithm.java` — Cloudflare two-counter algorithm with `evaluate()` for unit testing
- `sliding_window.lua` — atomic Redis implementation (GET prev, INCR curr, weighted estimate, EXPIRE)
- `RateLimitAlgorithm.java` — converted to `sealed interface … permits TokenBucketAlgorithm, SlidingWindowAlgorithm`; added `buildLuaKeys(String, long)` method
- `TokenBucketAlgorithm.java` — added `buildLuaKeys()` returning `List.of(bucketKey)`
- `LimitConfig.java` — record with `tokenBucket()` / `slidingWindow()` factories; algorithm switchable per endpoint
- `LuaScriptExecutor.java` — `@Component` that loads Lua scripts from classpath and executes via `RedisScript.of()`; no raw Redis commands
- `SlidingWindowAlgorithmTest.java` — 21 pure-Java unit tests (exact limit, limit+1, burst, window weight, parseResult, buildLuaArgs, buildLuaKeys)
- `SlidingWindowIT.java` — 3 Testcontainers integration tests against `redis:7-alpine` (exact limit, limit+1, previous-window weight decay)
- `pom.xml` — added `spring-boot-starter-test`; upgraded Testcontainers to 1.21.4 (Docker Desktop macOS fix)
- `mvn test`: 39 tests, 0 failures, 0 Checkstyle violations
- `mvn verify`: 42 tests (39 unit + 3 integration), 0 failures, BUILD SUCCESS

## [2026-04-15] — Week 2 — token bucket algorithm + Lua script
- `ClockProvider.java` + `SystemClockProvider.java` — injectable clock abstraction
- `RateLimitDecision.java` + `ClientIdentity.java` — model records
- `RateLimitAlgorithm.java` — strategy interface
- `TokenBucketAlgorithm.java` — token bucket with `evaluate()` for unit testing
- `token_bucket.lua` — atomic Redis implementation
- `TokenBucketAlgorithmTest.java` — 18 pure-Java unit tests
- Removed non-existent `org.testcontainers:redis` dependency from pom.xml
- `mvn test` passes: 18 tests, 0 Checkstyle violations

## [2026-04-15] — Week 1 — application entrypoint + config
- `FluxguardApplication.java` created — Spring Boot entry point under `com.fluxguard`
- `application.yml` created — Redis 50 ms timeout, Lettuce pool, Actuator, OTel log pattern
- `mvn test` passes: 0 Checkstyle violations, compiles clean

## [2026-04-15] — Week 1 — pom cleanup finalized
- `pom.xml` cleanup finalized
- Maven artifact renamed to `sentinelrate`
- Testcontainers setup standardized on `org.testcontainers:testcontainers` + `org.testcontainers:junit-jupiter`

## [2026-04-15] — Week 1 — pom.xml initialized
- pom.xml initialized

## [not started] — Week 1 — project initialized
- Repo structure created
- Project conventions documented
- Project status tracking initialized
- CHANGELOG.md initialized
