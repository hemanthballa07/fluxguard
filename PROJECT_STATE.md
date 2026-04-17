# PROJECT_STATE.md — Source of Truth
> This file is updated after every meaningful change.
> Claude must read this before starting any session.
> Human must update STATUS and NEXT_SESSION before closing.

---

## Current phase
**Phase:** Month 3 — Dynamic reconfiguration + feature flags
**Week:** 9 complete → moving to Week 10
**Overall status:** IN PROGRESS

---

## Phase tracker

| Phase   | Description                             |  Status        |
|---------|-----------------------------------------|----------------|
| Month 1 | Core algorithms + Redis + deployment.   | ✅ Complete    |
| Month 2 | Observability + benchmarks              | 🔵 In progress |
| Month 3 | Dynamic reconfiguration + feature flags | ⬜ Not started |

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
| 10   | Feature flags + dark launch         | ⬜     | —                                                                                                                                             |
| 11   | Kill switch + audit log             | ⬜     | —                                                                                                                                             |
| 12   | Integration + final polish          | ⬜     | —                                                                                                                                             |

---

## Last session log
**Date:** 2026-04-16
**Duration:** ~120 min
**What was completed:**
- Wrote ADR-004: Redis hash as config source of truth; amends CLAUDE.md rule re: Redis callers
- Created `ConfigService` interface (getConfig, getAllConfigs, putConfig, removeConfig, isKillSwitchActive, setKillSwitch)
- Created `RedisConfigService` (no @Component — @Bean only): HGET/HSET/HDEL on `fluxguard:configs` hash; kill switch via `fluxguard:kill-switch` key; flat JSON serialization with pattern matching
- Created `LimitConfigRequest` record (algorithm, capacity, refillRatePerSecond, limit, windowMs)
- Created `AdminController` with 5 endpoints (GET/PUT/DELETE configs, activate/deactivate kill switch)
- Updated `RateLimitConfiguration`: added `@Bean ConfigService` + `@Bean CommandLineRunner` seed (idempotent)
- Updated `RateLimitFilter`: removed static Map; injects ConfigService; kill-switch check first in preHandle()
- Updated `RateLimitFilterTest` + `RateLimitFilterTracingTest`: replaced Map fixtures with Mockito ConfigService stubs
- Added `RedisConfigServiceTest` (12 tests) + `AdminControllerTest` (9 tests)
- `mvn verify` — BUILD SUCCESS, 111 tests (104 unit + 7 IT)

**Files changed:**
- `docs/adr/ADR-004-config-service-storage.md` — new
- `src/main/java/com/fluxguard/config/ConfigService.java` — new
- `src/main/java/com/fluxguard/config/RedisConfigService.java` — new
- `src/main/java/com/fluxguard/api/AdminController.java` — new
- `src/main/java/com/fluxguard/model/LimitConfigRequest.java` — new
- `src/main/java/com/fluxguard/config/RateLimitConfiguration.java` — modified
- `src/main/java/com/fluxguard/filter/RateLimitFilter.java` — modified
- `src/test/java/com/fluxguard/config/RedisConfigServiceTest.java` — new
- `src/test/java/com/fluxguard/api/AdminControllerTest.java` — new
- `src/test/java/com/fluxguard/filter/RateLimitFilterTest.java` — modified
- `src/test/java/com/fluxguard/filter/RateLimitFilterTracingTest.java` — modified

**Decisions made:**
- Redis as source of truth from Week 9 (not Week 10 migration) — cross-instance consistency requirement
- No InMemoryConfigService as production code; test-only stubs via Mockito
- RedisConfigService registered only via @Bean in RateLimitConfiguration, not @Component
- Malformed Redis entries: log WARN + skip in getAllConfigs(); Optional.empty() in getConfig()
- AdminController unauthenticated (Week 11 adds security)

**Tests status:**
- Unit tests: 104 (all pass)
- Integration tests: 7 (all pass)
- Build: `mvn verify` — BUILD SUCCESS

---

## Current session (in progress)
**Started:** —
**Working on:** —
**Blockers:** none

---

## Next session pickup
**First task:** Week 10 — Feature flags + dark launch. Design per-client / per-endpoint flag rollout using `HashUtil` (consistent hashing). Write ADR-005 first.
**Context needed:** Read CLAUDE.md + this file only
**Open questions:** None — log MDC bridge deferred to Week 12 polish.

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
| `AdminController`                       | `api/`                     | ✅ Built + tested | 9 unit               | 5 endpoints: GET/PUT/DELETE configs, activate/deactivate kill switch; no auth (Week 11)                                                    |
| `ConfigService`                         | `config/`                  | ✅ Built + tested | —                    | Interface; implemented by `RedisConfigService`                                                                                             |
| `RedisConfigService`                    | `config/`                  | ✅ Built + tested | 12 unit              | Redis hash source of truth; flat JSON; kill switch key; @Bean only (no @Component)                                                        |
| `LimitConfigRequest`                    | `model/`                   | ✅ Built          | via AdminControllerTest | Record: algorithm + typed fields (capacity, refillRatePerSecond, limit, windowMs)                                                       |
| `WebMvcConfig`                          | `filter/`                  | ✅ Built          | —                    | `WebMvcConfigurer`; registers `RateLimitFilter` for all paths                                                                              |
| `RateLimitConfiguration`                | `config/`                  | ✅ Built          | —                    | `@Configuration`; `Map<String,LimitConfig>` bean + Resilience4j `CircuitBreaker` bean + `Tracer` bean                                     |
| `Dockerfile`                            | `/`                        | ✅ Built          | —                    | Multi-stage; `maven:3.9-eclipse-temurin-17` build + `eclipse-temurin:17-jre-alpine` runtime; non-root user                                 |
| GitHub Actions CI/CD                    | `.github/workflows/ci.yml` | ✅ Built          | —                    | `build-and-test` job (`mvn verify`) + `docker-build` job (ECR push on `push` events)                                                       |
| Grafana dashboards                      | `grafana/dashboards/`      | ✅ Built          | —                    | Three dashboards: traffic overview, limiter internals, per-client breakdown                                                                |
| Grafana provisioning                    | `grafana/provisioning/`    | ✅ Built          | —                    | File-based provider + default Prometheus datasource                                                                                        |
| OpenTelemetry tracing                   | `filter/`, `redis/`        | ✅ Built + tested | 12 unit + Jaeger ✅  | Parent `rate_limit.decision` + child `redis.lua_script`; gRPC export verified; log MDC bridge gap (known issue)                            |
| ECS/Fargate deployment                  | `scripts/deploy.sh`        | ❌ Not done       | —                    | Deferred — ECS service/task definition not yet wired                                                                                       |

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
| Log `trace_id=` always empty — OTel starter missing `opentelemetry-logback-mdc-1.0` dep + `logback-spring.xml`; filter has no INFO log on success path | Low | `pom.xml`, `application.yml` | Open |

---

## Resume bullets tracker
> Add bullet here only when the feature is fully built and tested.

**Month 1 bullets — EARNED ✅:**
- [x] Token bucket + sliding window counter implemented and tested (60 tests, 0 failures)
- [x] Redis Lua scripts atomic — `token_bucket.lua` + `sliding_window.lua` via `LuaScriptExecutor`
- [x] Fail-open with Resilience4j circuit breaker — `RateLimitFilter` wired; tagged counters for `redis_error` / `circuit_open`
- [x] Dockerfile — multi-stage build; non-root runtime image; ECR-ready
- [x] CI/CD via GitHub Actions — `mvn verify` + Checkstyle on every push; Docker image built and pushed to ECR

**Month 2 bullets (not yet earned):**
- [x] Prometheus metrics with p50/p95/p99
- [x] Grafana dashboards (3 views)
- [x] OpenTelemetry traces — `rate_limit.decision` + `redis.lua_script` spans; Jaeger verified; 90 tests (83 unit + 7 IT)
- [x] k6 benchmark results with real numbers

**Month 3 bullets (not yet earned):**
- [x] Dynamic reconfiguration live — ConfigService + RedisConfigService + AdminController; 111 tests passing
- [ ] Dark launch mode
- [ ] Kill switch + audit log
