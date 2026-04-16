# PROJECT_STATE.md — Source of Truth
> This file is updated after every meaningful change.
> Claude must read this before starting any session.
> Human must update STATUS and NEXT_SESSION before closing.

---

## Current phase
**Phase:** Month 2 — Observability + Benchmarks
**Week:** 6 of 12
**Overall status:** IN PROGRESS

---

## Phase tracker

| Phase | Description | Status |
|-------|-------------|--------|
| Month 1 | Core algorithms + Redis + deployment | ✅ Complete |
| Month 2 | Observability + benchmarks | 🟨 In progress |
| Month 3 | Dynamic reconfiguration + feature flags | ⬜ Not started |

---

## Week tracker

| Week | Goal | Status | Completed |
|------|------|--------|-----------|
| 1 | Project skeleton + Redis setup + CI | ✅ | `pom.xml`, `FluxguardApplication.java`, `application.yml` |
| 2 | Token bucket algorithm + Lua script | ✅ | `RateLimitAlgorithm`, `TokenBucketAlgorithm`, `token_bucket.lua`, models, `ClockProvider`, 18 unit tests |
| 3 | Sliding window counter | ✅ | `SlidingWindowAlgorithm`, `sliding_window.lua`, sealed interface + `buildLuaKeys`, `LimitConfig`, `LuaScriptExecutor`, 21 unit + 3 IT |
| 4 | Fail-open + ECS deployment | ✅ | `RateLimitFilter`, `WebMvcConfig`, `RateLimitConfiguration`, `RateLimitFilterTest`, `RateLimitFilterIT`, Resilience4j CB |
| 5 | Prometheus metrics | ✅ | `PrometheusMetricsCollector`, updated `RateLimitFilter`, `LuaScriptExecutor`, `PrometheusMetricsCollectorTest`, updated `RateLimitFilterTest` |
| 6 | Grafana dashboards | ⬜ | — |
| 7 | OpenTelemetry tracing | ⬜ | — |
| 8 | k6 benchmarks + README | ⬜ | — |
| 9 | Config service design | ⬜ | — |
| 10 | Feature flags + dark launch | ⬜ | — |
| 11 | Kill switch + audit log | ⬜ | — |
| 12 | Integration + final polish | ⬜ | — |

---

## Last session log
**Date:** 2026-04-15
**Duration:** 1 session
**What was completed:**
- Created `PrometheusMetricsCollector` (`metrics/`) — all 5 metric families; public constants for names + tags; lazy Micrometer registration; `publishPercentileHistogram()` on both timers for `_bucket` output
- Updated `RateLimitFilter` — replaced `MeterRegistry` + inline counters with `PrometheusMetricsCollector`; `DecisionOutcome` private record; `executeAndApply()` + `recordMetrics()` helpers; duration only recorded on real rate-limit decisions (not 400 or unknown path)
- Updated `LuaScriptExecutor` — injected `PrometheusMetricsCollector`; `redis.script.duration` timed around `redisTemplate.execute()` only; timer recorded before null check
- Created `PrometheusMetricsCollectorTest` — 11 pure-Java unit tests; counter isolation; timer count assertions
- Updated `RateLimitFilterTest` — 21 tests (7 new for allowed/denied counters, duration on allow/deny/failopen, no-duration guards); counter names updated to new metric names
- Verified `/actuator/prometheus` with live stack — all 5 families present

**Files changed:**
- `src/main/java/com/fluxguard/metrics/PrometheusMetricsCollector.java` (new)
- `src/main/java/com/fluxguard/filter/RateLimitFilter.java` (refactored)
- `src/main/java/com/fluxguard/redis/LuaScriptExecutor.java` (metrics added)
- `src/test/java/com/fluxguard/metrics/PrometheusMetricsCollectorTest.java` (new)
- `src/test/java/com/fluxguard/filter/RateLimitFilterTest.java` (updated)
- `PROJECT_STATE.md`, `CHANGELOG.md`

**Decisions made:**
- All metric name/tag constants are `public static final` in `PrometheusMetricsCollector` — tests in other packages can reference them without string literals
- `publishPercentileHistogram()` added to both timers so Prometheus output includes `_bucket` lines for p50/p95/p99 in Grafana
- Duration NOT recorded for 400 (missing header) or unknown path (no limiting) — only real rate-limit decisions are timed
- `DecisionOutcome` private record carries `failOpenReason` (null for non-fail-open paths) — avoids leaking reason semantics into `applyDecision`

**Tests status:**
- `mvn test` — 71 PASS (21 SlidingWindow + 18 TokenBucket + 21 RateLimitFilter + 11 PrometheusMetricsCollector), 0 failures, 0 Checkstyle violations
- `mvn verify` — 78 PASS (71 unit + 7 IT), 0 failures, BUILD SUCCESS

---

## Current session (in progress)
**Started:** 2026-04-15
**Working on:** Week 5 — Prometheus metrics
**Blockers:** none

---

## Next session pickup
**Start here:** Week 6 — Grafana dashboards
**First task:** Create a Grafana dashboard JSON in `docker/grafana/` with three views: (1) rate-limit traffic (allowed/denied/failopen rates); (2) p50/p95/p99 decision latency from `rate_limit_duration_seconds`; (3) Redis script latency from `redis_script_duration_seconds`
**Context needed:** Read CLAUDE.md + this file only
**Open questions:** none

---

## Architecture snapshot
> Updated after every session. Shows the build and test status of every major component.

| Component | Package | Status | Test coverage | Notes |
|-----------|---------|--------|---------------|-------|
| `RateLimitAlgorithm` (sealed interface) | `algorithm/` | ✅ Built | — | `luaScriptName`, `buildLuaKeys`, `buildLuaArgs`, `parseResult` |
| `TokenBucketAlgorithm` | `algorithm/` | ✅ Built + tested | 18 unit | Mirrors `token_bucket.lua`; `evaluate()` for unit testing |
| `SlidingWindowAlgorithm` | `algorithm/` | ✅ Built + tested | 21 unit + 3 IT | Cloudflare two-counter; `evaluate()` for unit testing |
| `token_bucket.lua` | `resources/lua/` | ✅ Built | via IT | HMGET → refill → HSET + EXPIRE |
| `sliding_window.lua` | `resources/lua/` | ✅ Built | 3 IT | GET prev + INCR curr + EXPIRE; weighted estimate |
| `LuaScriptExecutor` | `redis/` | ✅ Built | via IT | `@Component`; `RedisScript.of()`; no raw Redis commands; `redis.script.duration` histogram |
| `LimitConfig` | `config/` | ✅ Built | — | Record; `tokenBucket()` + `slidingWindow()` factories |
| `ClockProvider` / `SystemClockProvider` | `util/` | ✅ Built | — | Testable clock abstraction; `@Component` |
| `RateLimitDecision` | `model/` | ✅ Built | via algorithm tests | Record; `allow(remaining)` + `deny(resetAfterMs)` |
| `ClientIdentity` | `model/` | ✅ Built | — | Record; `of(clientId, endpoint)` → `rl:{id}:{path}` |
| `RateLimitFilter` | `filter/` | ✅ Built + tested | 21 unit + 4 IT | `HandlerInterceptor`; fail-open on Redis error + circuit open; 429 + Retry-After on deny; full metrics via `PrometheusMetricsCollector` |
| `RateLimitException` | `exception/` | ✅ Built | — | Unchecked; carries `retryAfterMs`; thrown by filter on deny |
| `RedisUnavailableException` | `exception/` | ✅ Built | — | Unchecked; thrown by LuaScriptExecutor on null result |
| `PrometheusMetricsCollector` | `metrics/` | ✅ Built + tested | 11 unit | 5 metric families; `publishPercentileHistogram()` on timers |
| `AdminController` | `api/` | ❌ Not built | — | Month 3 |
| `ConfigService` | `config/` | ❌ Not built | — | Month 3 — dynamic reconfiguration |
| `WebMvcConfig` | `filter/` | ✅ Built | — | `WebMvcConfigurer`; registers `RateLimitFilter` for all paths |
| `RateLimitConfiguration` | `config/` | ✅ Built | — | `@Configuration`; `Map<String,LimitConfig>` bean + Resilience4j `CircuitBreaker` bean |
| `Dockerfile` | `/` | ✅ Built | — | Multi-stage; `maven:3.9-eclipse-temurin-17` build + `eclipse-temurin:17-jre-alpine` runtime; non-root user |
| GitHub Actions CI/CD | `.github/workflows/ci.yml` | ✅ Built | — | `build-and-test` job (`mvn verify`) + `docker-build` job (ECR push on `push` events) |
| ECS/Fargate deployment | `scripts/deploy.sh` | ❌ Not done | — | Deferred — ECS service/task definition not yet wired |

---

## Blockers log
| Date | Blocker | Resolution | Resolved |
|------|---------|------------|---------|
| — | — | — | — |

---

## Architecture decisions log
| Date | Decision | ADR | Status |
|------|----------|-----|--------|
| (pre-session) | Algorithm selection: token bucket + sliding window counter | ADR-001 | Accepted |
| (pre-session) | Data store: Redis + Lua scripts via ElastiCache | ADR-002 | Accepted |
| (pre-session) | Fail-open on Redis unavailability | ADR-003 | Accepted |

---

## Benchmark results log
| Date | Scenario | RPS | p50 | p95 | p99 | Notes |
|------|----------|-----|-----|-----|-----|-------|
| — | — | — | — | — | — | — |

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

**Month 2 bullets (not yet earned):**
- [x] Prometheus metrics with p50/p95/p99
- [ ] Grafana dashboards (3 views)
- [ ] OpenTelemetry traces
- [ ] k6 benchmark results with real numbers

**Month 3 bullets (not yet earned):**
- [ ] Dynamic reconfiguration live
- [ ] Dark launch mode
- [ ] Kill switch + audit log
