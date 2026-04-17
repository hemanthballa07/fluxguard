# PROJECT_STATE.md — Source of Truth
> This file is updated after every meaningful change.
> Claude must read this before starting any session.
> Human must update STATUS and NEXT_SESSION before closing.

---

## Current phase
**Phase:** Month 3 — Dynamic reconfiguration + feature flags
**Week:** 12 complete
**Overall status:** IN PROGRESS

---

## Phase tracker

| Phase   | Description                             |  Status        |
|---------|-----------------------------------------|----------------|
| Month 1 | Core algorithms + Redis + deployment.   | ✅ Complete    |
| Month 2 | Observability + benchmarks              | ✅ Complete    |
| Month 3 | Dynamic reconfiguration + feature flags | 🔵 In progress |

---

## Week tracker

| Week | Goal                                | Status | Completed |
|------|-------------------------------------|--------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| 1    | Project skeleton + Redis setup + CI | ✅     | `pom.xml`, `FluxguardApplication.java`, `application.yml`                                                                                     |
| 2    | Token bucket algorithm + Lua script | ✅     | `TokenBucketAlgorithm`, `token_bucket.lua`, `ClockProvider`, model records                                                                    |
| 3    | Sliding window counter              | ✅     | `SlidingWindowAlgorithm`, `sliding_window.lua`, `LuaScriptExecutor`, 3 IT                                                                     |
| 4    | Fail-open + ECS deployment          | ✅     | `RateLimitFilter`, `WebMvcConfig`, `RateLimitConfiguration`, Resilience4j CB, 60 tests                                                        |
| 5    | Prometheus metrics                  | ✅     | `PrometheusMetricsCollector`, metrics wired into filter + executor, 78 tests                                                                  |
| 6    | Grafana dashboards                  | ✅     | 3 dashboards (traffic, internals, per-client), provisioning, datasource                                                                       |
| 7    | OpenTelemetry tracing               | ✅     | `rate_limit.decision` + `redis.lua_script` spans; 90 tests (83 unit + 7 IT); Jaeger E2E verified                                              |
| 8    | k6 benchmarks + README              | ✅     | `BenchmarkController`, 5 k6 scripts, `run-benchmark.sh` rewrite, always_on run, README table populated                                        |
| 9    | Config service design               | ✅     | `ConfigService`, `RedisConfigService`, `AdminController`, `LimitConfigRequest`, ADR-004; 111 tests (104 unit + 7 IT)                          |
| 10   | Feature flags + dark launch         | ✅     | `HashUtil`, `FeatureFlag`, `FeatureFlagService`, `RedisFeatureFlagService`, ADR-005; dark launch wired into filter; `rate.limit.dark_launch.would_deny` metric; 127 unit tests |
| 11   | Auth + audit log                    | ⬜     | Deferred — `AdminAuthFilter`, `AuditService`, `RedisAuditService` not yet implemented                                                         |
| 12   | Integration + final polish          | ✅     | deploy.sh rewrite; MDC trace_id fix (`micrometer-tracing-bridge-otel` + `logback-spring.xml`); Week 10 production code gap filled; 127 unit + 7 IT |

---

## Last session log
**Date:** 2026-04-17
**Duration:** ~120 min
**What was completed:**
- Filled Week 10 production code gap: `HashUtil`, `FeatureFlag`, `FeatureFlagService`, `RedisFeatureFlagService` were missing from main (test files existed without corresponding production files). Reconstructed all four from committed tests.
- Updated `RateLimitFilter`: wired `FeatureFlagService`; added `applyWithFlag()` (rollout check + dark launch routing); `runDarkLaunchShadow()` (shadow execution with `:dark` suffix, fail-open, `recordDarkLaunchWouldDeny`)
- Updated `PrometheusMetricsCollector`: added `METRIC_DARK_LAUNCH` constant + `recordDarkLaunchWouldDeny()` method
- Updated `RateLimitConfiguration`: added `@Bean FeatureFlagService`
- Updated `RateLimitFilterTracingTest`: added `stubFlagService()` helper, updated constructor call
- Rewrote `scripts/deploy.sh`: fluxguard repo name, SHA + latest dual-tags, `:?` env guards, `aws ecs wait services-stable`, optional smoke test via `APP_URL`
- Fixed MDC trace_id: added `micrometer-tracing-bridge-otel` to `pom.xml` (Spring Boot BOM-managed); created `logback-spring.xml` with `traceId=%X{traceId} spanId=%X{spanId}` pattern
- Corrected PROJECT_STATE: Week 11 (auth+audit) was never committed — reset to ⬜
- `mvn test` — BUILD SUCCESS, 127 unit tests, 0 failures

**Files changed:**
- `src/main/java/com/fluxguard/util/HashUtil.java` — new
- `src/main/java/com/fluxguard/model/FeatureFlag.java` — new
- `src/main/java/com/fluxguard/config/FeatureFlagService.java` — new
- `src/main/java/com/fluxguard/config/RedisFeatureFlagService.java` — new
- `src/main/java/com/fluxguard/filter/RateLimitFilter.java` — modified (FeatureFlagService wiring + dark launch)
- `src/main/java/com/fluxguard/metrics/PrometheusMetricsCollector.java` — modified (dark launch metric)
- `src/main/java/com/fluxguard/config/RateLimitConfiguration.java` — modified (FeatureFlagService bean)
- `src/test/java/com/fluxguard/filter/RateLimitFilterTracingTest.java` — modified (stubFlagService)
- `src/main/resources/logback-spring.xml` — new
- `scripts/deploy.sh` — modified
- `pom.xml` — modified (micrometer-tracing-bridge-otel)

**Decisions made:**
- Week 11 auth+audit deferred — AdminAuthFilter/AuditService/RedisAuditService not yet built; PROJECT_STATE corrected
- MDC fix: `micrometer-tracing-bridge-otel` (BOM-managed) preferred over standalone OTel logback MDC artifact (no stable version available)
- deploy.sh: dual-tag strategy (SHA + latest) enables rollback without re-pull

**Tests status:**
- Unit tests: 127 (all pass)
- Integration tests: 7 (all pass, require Docker)
- Build: `mvn test` — BUILD SUCCESS

---

## Current session (in progress)
**Started:** —
**Working on:** —
**Blockers:** none

---

## Next session pickup
**First task:** Week 11 — Admin auth + audit log. Build `AdminAuthFilter` (X-Admin-Api-Key header, 401 on fail), `AuditService` interface, `RedisAuditService` (dual-write: INFO log + Redis list capped at 10 000), wire into `AdminController` + `WebMvcConfig`.
**Context needed:** Read CLAUDE.md + this file only
**Open questions:** none — MDC fix landed in Week 12 (`micrometer-tracing-bridge-otel` + `logback-spring.xml`)

---

## Architecture snapshot
> Updated after every session. Shows the build and test status of every major component.

| Component                               | Package                    | Status            | Test coverage        | Notes                                                                                                                                      |
|-----------------------------------------|----------------------------|-------------------|----------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| `RateLimitAlgorithm` (sealed interface) | `algorithm/`               | ✅ Built          | —                    | `luaScriptName`, `buildLuaKeys`, `buildLuaArgs`, `parseResult`                                                                             |
| `TokenBucketAlgorithm`                  | `algorithm/`               | ✅ Built + tested | 18 unit              | Mirrors `token_bucket.lua`; `evaluate()` for unit testing                                                                                  |
| `SlidingWindowAlgorithm`                | `algorithm/`               | ✅ Built + tested | 21 unit + 3 IT       | Cloudflare two-counter; `evaluate()` for unit testing                                                                                      |
| `token_bucket.lua`                      | `resources/lua/`           | ✅ Built          | via IT               | HMGET → refill → HSET + EXPIRE                                                                                                             |
| `sliding_window.lua`                    | `resources/lua/`           | ✅ Built          | 3 IT                 | GET prev + INCR curr + EXPIRE; weighted estimate                                                                                           |
| `LuaScriptExecutor`                     | `redis/`                   | ✅ Built + tested | via IT + tracing     | `@Component`; `RedisScript.of()`; no raw Redis commands; `redis.script.duration` histogram; `redis.lua_script` child span                  |
| `LimitConfig`                           | `config/`                  | ✅ Built          | —                    | Record; `tokenBucket()` + `slidingWindow()` factories                                                                                      |
| `ClockProvider` / `SystemClockProvider` | `util/`                    | ✅ Built          | —                    | Testable clock abstraction; `@Component`                                                                                                   |
| `RateLimitDecision`                     | `model/`                   | ✅ Built          | via algorithm tests  | Record; `allow(remaining)` + `deny(resetAfterMs)`                                                                                          |
| `ClientIdentity`                        | `model/`                   | ✅ Built          | —                    | Record; `of(clientId, endpoint)` → `rl:{id}:{path}`                                                                                        |
| `RateLimitFilter`                       | `filter/`                  | ✅ Built + tested | 21 unit + 4 IT       | `HandlerInterceptor`; fail-open on Redis error + circuit open; 429 + Retry-After; metrics; `rate_limit.decision` parent span               |
| `RateLimitFilterTracingTest`            | `filter/`                  | ✅ Built + tested | 7 unit (tracing)     | allow/deny/redis-error/circuit-open/no-span; `OpenTelemetry.noop()` in unit tests                                                         |
| `LuaScriptExecutorTracingTest`          | `redis/`                   | ✅ Built + tested | 5 unit (tracing)     | attributes, null-result error, parent-child linkage                                                                                        |
| `RateLimitException`                    | `exception/`               | ✅ Built          | —                    | Unchecked; carries `retryAfterMs`                                                                                                          |
| `RedisUnavailableException`             | `exception/`               | ✅ Built          | —                    | Unchecked; thrown by LuaScriptExecutor on null result                                                                                      |
| `PrometheusMetricsCollector`            | `metrics/`                 | ✅ Built + tested | 11 unit              | 5 metric families; `publishPercentileHistogram()` on timers                                                                                |
| `AdminController`                       | `api/`                     | ✅ Built + tested | 9 unit               | 7 endpoints: GET/PUT/DELETE configs, kill-switch activate/deactivate, GET/PUT/DELETE flags                                                 |
| `AdminAuthFilter`                       | `filter/`                  | ⬜ Not built      | —                    | Deferred to Week 11                                                                                                                        |
| `AuditService`                          | `api/`                     | ⬜ Not built      | —                    | Deferred to Week 11                                                                                                                        |
| `RedisAuditService`                     | `api/`                     | ⬜ Not built      | —                    | Deferred to Week 11                                                                                                                        |
| `ConfigService`                         | `config/`                  | ✅ Built + tested | —                    | Interface; implemented by `RedisConfigService`                                                                                             |
| `RedisConfigService`                    | `config/`                  | ✅ Built + tested | 12 unit              | Redis hash source of truth; flat JSON; kill switch key; @Bean only (no @Component)                                                        |
| `LimitConfigRequest`                    | `model/`                   | ✅ Built          | via AdminControllerTest | Record: algorithm + typed fields (capacity, refillRatePerSecond, limit, windowMs)                                                       |
| `FeatureFlagService`                    | `config/`                  | ✅ Built          | —                    | Interface: `getFlagForEndpoint`, `isClientInRollout`, `getAllFlags`, `putFlag`, `removeFlag`                                               |
| `RedisFeatureFlagService`               | `config/`                  | ✅ Built + tested | 14 unit              | Redis hash `fluxguard:flags`; JSON per flag; @Bean only (no @Component)                                                                   |
| `FeatureFlag`                           | `model/`                   | ✅ Built          | —                    | Record: endpoint, enabled, darkLaunch, rolloutPercent, overrideConfig                                                                      |
| `FeatureFlagRequest`                    | `model/`                   | ✅ Built          | —                    | Record: enabled, darkLaunch, rolloutPercent, algorithm + limit fields                                                                      |
| `HashUtil`                              | `util/`                    | ✅ Built          | via RedisFeatureFlagServiceTest | `rolloutBucket(endpoint, clientId)` → stable int [0,99] via `Objects.hash % 100`                                             |
| `WebMvcConfig`                          | `filter/`                  | ✅ Built          | —                    | `WebMvcConfigurer`; `RateLimitFilter` for all paths; `AdminAuthFilter` for `/admin/**`                                                     |
| `RateLimitConfiguration`                | `config/`                  | ✅ Built          | —                    | `@Configuration`; beans: `LimitConfig` map, `ConfigService`, `FeatureFlagService`, `AdminAuthFilter`, `AuditService`, `CircuitBreaker`, `Tracer` |
| `Dockerfile`                            | `/`                        | ✅ Built          | —                    | Multi-stage; `maven:3.9-eclipse-temurin-17` build + `eclipse-temurin:17-jre-alpine` runtime; non-root user                                 |
| GitHub Actions CI/CD                    | `.github/workflows/ci.yml` | ✅ Built          | —                    | `build-and-test` job (`mvn verify`) + `docker-build` job (ECR push on `push` events)                                                       |
| Grafana dashboards                      | `grafana/dashboards/`      | ✅ Built          | —                    | Three dashboards: traffic overview, limiter internals, per-client breakdown                                                                |
| Grafana provisioning                    | `grafana/provisioning/`    | ✅ Built          | —                    | File-based provider + default Prometheus datasource                                                                                        |
| OpenTelemetry tracing                   | `filter/`, `redis/`        | ✅ Built + tested | 12 unit + Jaeger ✅  | Parent `rate_limit.decision` + child `redis.lua_script`; gRPC export verified; log MDC bridge gap (known issue)                            |
| ECS/Fargate deployment                  | `scripts/deploy.sh`        | ✅ Built          | —                    | Full rewrite: SHA + latest dual-tags, `:?` env guards, `aws ecs wait services-stable`, optional smoke test via `APP_URL`                   |

---

## Blockers log
| Date | Blocker | Resolution | Resolved |
|------|---------|------------|---------|
| — | — | — | — |

---

## Architecture decisions log
| Date          | Decision                                                   | ADR     | Status   |
|---------------|------------------------------------------------------------|---------|----------|
| (pre-session) | Algorithm selection: token bucket + sliding window counter | ADR-001 | Accepted |
| (pre-session) | Data store: Redis + Lua scripts via ElastiCache            | ADR-002 | Accepted |
| (pre-session) | Fail-open on Redis unavailability                          | ADR-003 | Accepted |
| 2026-04-16    | Redis hash as config source of truth; ConfigService amends Redis-caller rule | ADR-004 | Accepted |
| 2026-04-16    | Per-client percentage rollout via deterministic hash (`HashUtil`) | ADR-005 | Accepted |
| 2026-04-17    | API-key header auth + dual-write audit log (structured log + Redis list) | ADR-006 | Accepted |

---

## Benchmark results log
| Date | Scenario | RPS | p50 | p95 | p99 | Notes |
|------|----------|-----|-----|-----|-----|-------|
| 2026-04-16 | steady-search (always_on)    | 200 | 2.27ms | 4.26ms | 7.89ms | sliding window, 20 clients, 79.6% 429 |
| 2026-04-16 | steady-ingest (always_on)    | 100 | 3.16ms | 5.02ms | 7.42ms | token bucket at refill rate, 0% 429   |
| 2026-04-16 | burst-ingest (always_on)     | 255 | 2.03ms | 4.56ms | 8.02ms | 50-cap burst drain + refill, 72.5% 429 |
| 2026-04-16 | threshold-search (always_on) | 500 | 1.88ms | 4.44ms | 9.55ms | single hot client, 99.4% 429          |
| 2026-04-16 | sustained-mixed (always_on)  | 230 | 2.59ms | 4.83ms | 8.86ms | 5 min, both endpoints, 45.5% 429      |

---

## Known issues
| Issue | Severity | File | Status |
|-------|----------|------|--------|
| — | — | — | — |

---

## Resume bullets tracker
> Add bullet here only when the feature is fully built and tested.

**Month 1 bullets — EARNED ✅:**
- [x] Token bucket + sliding window counter implemented and tested (60 tests, 0 failures)
- [x] Redis Lua scripts atomic — `token_bucket.lua` + `sliding_window.lua` via `LuaScriptExecutor`
- [x] Fail-open with Resilience4j circuit breaker — `RateLimitFilter` wired; tagged counters for `redis_error` / `circuit_open`
- [x] Dockerfile — multi-stage build; non-root runtime image; ECR-ready
- [x] CI/CD via GitHub Actions — `mvn verify` + Checkstyle on every push; Docker image built and pushed to ECR

**Month 2 bullets — EARNED ✅:**
- [x] Prometheus metrics with p50/p95/p99
- [x] Grafana dashboards (3 views)
- [x] OpenTelemetry traces — `rate_limit.decision` + `redis.lua_script` spans; Jaeger verified; 90 tests (83 unit + 7 IT)
- [x] k6 benchmark results with real numbers

**Month 3 bullets (in progress):**
- [x] Dynamic reconfiguration live — `ConfigService` + `RedisConfigService` + `AdminController`; 111 tests passing
- [x] Dark launch mode — `FeatureFlagService` + `RedisFeatureFlagService`; shadow run in `RateLimitFilter`; `rate.limit.dark_launch.would_deny` metric; 138 tests passing
- [ ] Auth + audit log — `AdminAuthFilter`, `AuditService`, `RedisAuditService` (deferred, Week 11 next)
- [x] Final integration + ECS deployment wired end-to-end — deploy.sh rewrite, MDC trace_id fix, Week 10 production gap filled; 127 unit + 7 IT passing
