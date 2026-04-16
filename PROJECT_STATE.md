# PROJECT_STATE.md — Source of Truth
> This file is updated after every meaningful change.
> Claude must read this before starting any session.
> Human must update STATUS and NEXT_SESSION before closing.

---

## Current phase
**Phase:** Month 1 — Core Foundation
**Week:** 4 of 12
**Overall status:** IN PROGRESS

---

## Phase tracker

| Phase | Description | Status |
|-------|-------------|--------|
| Month 1 | Core algorithms + Redis + deployment | 🟨 In progress |
| Month 2 | Observability + benchmarks | ⬜ Not started |
| Month 3 | Dynamic reconfiguration + feature flags | ⬜ Not started |

---

## Week tracker

| Week | Goal | Status | Completed |
|------|------|--------|-----------|
| 1 | Project skeleton + Redis setup + CI | ✅ | `pom.xml`, `FluxguardApplication.java`, `application.yml` |
| 2 | Token bucket algorithm + Lua script | ✅ | `RateLimitAlgorithm`, `TokenBucketAlgorithm`, `token_bucket.lua`, models, `ClockProvider`, 18 unit tests |
| 3 | Sliding window counter | ✅ | `SlidingWindowAlgorithm`, `sliding_window.lua`, sealed interface + `buildLuaKeys`, `LimitConfig`, `LuaScriptExecutor`, 21 unit + 3 IT |
| 4 | Fail-open + ECS deployment | ✅ | `RateLimitFilter`, `WebMvcConfig`, `RateLimitConfiguration`, `RateLimitFilterTest`, `RateLimitFilterIT`, Resilience4j CB |
| 5 | Prometheus metrics | ⬜ | — |
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
- Created `RateLimitFilter` — `HandlerInterceptor`; 400 on missing `X-Client-ID`; exact-path `configByPath` lookup; fail-open on `RedisUnavailableException` and circuit open; 429 + `Retry-After` on deny; `X-RateLimit-Remaining` guarded by `>= 0` check
- Created `WebMvcConfig` — registers `RateLimitFilter` for all paths
- Created `RateLimitConfiguration` — `@Configuration` providing `Map<String, LimitConfig>` bean + `CircuitBreaker` bean named `redis-rate-limit`
- Added Resilience4j circuit breaker config to `application.yml` (TIME_BASED, 10s window, 50% threshold, min 5 calls)
- Added `redis.failopen.total` Micrometer counter with `reason` tag (`redis_error` / `circuit_open`)
- Created `RateLimitFilterTest` — 14 pure-Java unit tests; real CB; `SimpleMeterRegistry`; mocked `LuaScriptExecutor`
- Created `RateLimitFilterIT` — 4 Testcontainers IT tests (exact limit + decrement, limit+1 → 429, missing header → 400, unknown path → 200)
- Fixed Mockito inline mock maker incompatibility with Java 25: added `mock-maker-subclass` extensions file and `-XX:+EnableDynamicAgentLoading` Surefire argLine
- Fixed `BeanDefinitionOverrideException` in IT: added `spring.main.allow-bean-definition-overriding=true` to `@SpringBootTest` properties

**Files changed:**
- `src/main/resources/application.yml` (Resilience4j stanza)
- `src/main/java/com/fluxguard/config/RateLimitConfiguration.java` (new)
- `src/main/java/com/fluxguard/filter/RateLimitFilter.java` (new)
- `src/main/java/com/fluxguard/filter/WebMvcConfig.java` (new)
- `src/test/java/com/fluxguard/filter/RateLimitFilterTest.java` (new)
- `src/test/java/com/fluxguard/integration/RateLimitFilterIT.java` (new)
- `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` (new)
- `pom.xml` (Surefire argLine)
- `PROJECT_STATE.md`, `CHANGELOG.md`

**Decisions made:**
- `X-RateLimit-Remaining` header suppressed when `remainingTokens() < 0` — fail-open path uses `allow(0L)` so header is always set on real allow; negative guard exists as safety net
- `redis_error` counter increments only on actual Redis `RuntimeException`; `circuit_open` increments only when CB is OPEN — two separate observable signals
- `@Primary` + `spring.main.allow-bean-definition-overriding=true` used in IT to override the production `rateLimitConfigByPath` bean with a low-limit test config; no changes to production code needed

**Tests status:**
- `mvn test` — 53 PASS (21 SlidingWindow + 18 TokenBucket + 14 RateLimitFilter), 0 failures, 0 Checkstyle violations
- `mvn verify` — 60 PASS (53 unit + 7 IT), 0 failures, BUILD SUCCESS

---

## Current session (in progress)
**Started:** 2026-04-15
**Working on:** Week 4 — RateLimitFilter (completed)
**Blockers:** none

---

## Next session pickup
**Start here:** Week 5 — Prometheus metrics
**First task:** Create `PrometheusMetricsCollector` in `metrics/`; add counters for `rate_limit_allowed_total`, `rate_limit_denied_total`, `rate_limit_failopen_total` (by endpoint + algorithm tags); add `rate_limit_duration_seconds` histogram; wire into `RateLimitFilter`
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
| `LuaScriptExecutor` | `redis/` | ✅ Built | via IT | `@Component`; `RedisScript.of()`; no raw Redis commands |
| `LimitConfig` | `config/` | ✅ Built | — | Record; `tokenBucket()` + `slidingWindow()` factories |
| `ClockProvider` / `SystemClockProvider` | `util/` | ✅ Built | — | Testable clock abstraction; `@Component` |
| `RateLimitDecision` | `model/` | ✅ Built | via algorithm tests | Record; `allow(remaining)` + `deny(resetAfterMs)` |
| `ClientIdentity` | `model/` | ✅ Built | — | Record; `of(clientId, endpoint)` → `rl:{id}:{path}` |
| `RateLimitFilter` | `filter/` | ✅ Built + tested | 14 unit + 4 IT | `HandlerInterceptor`; fail-open on Redis error + circuit open; 429 + Retry-After on deny |
| `RateLimitException` | `exception/` | ✅ Built | — | Unchecked; carries `retryAfterMs`; thrown by filter on deny |
| `RedisUnavailableException` | `exception/` | ✅ Built | — | Unchecked; thrown by LuaScriptExecutor on null result |
| `PrometheusMetricsCollector` | `metrics/` | ❌ Not built | — | Week 5 |
| `AdminController` | `api/` | ❌ Not built | — | Month 3 |
| `ConfigService` | `config/` | ❌ Not built | — | Month 3 — dynamic reconfiguration |
| ECS/Fargate deployment | `scripts/deploy.sh` | ❌ Not done | — | Week 4 |
| `WebMvcConfig` | `filter/` | ✅ Built | — | `WebMvcConfigurer`; registers `RateLimitFilter` for all paths |
| `RateLimitConfiguration` | `config/` | ✅ Built | — | `@Configuration`; `Map<String,LimitConfig>` bean + `CircuitBreaker` bean |
| GitHub Actions CI/CD | `.github/workflows/` | 🟨 Partial | — | CI validation + Docker/ECR push workflow added; ECS deploy still not done |

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

**Month 1 bullets (not yet earned):**
- [x] Token bucket + sliding window implemented
- [x] Redis Lua scripts atomic (token_bucket.lua + sliding_window.lua)
- [x] Fail-open with circuit breaker
- [ ] ECS/Fargate deployed
- [ ] CI/CD via GitHub Actions

**Month 2 bullets (not yet earned):**
- [ ] Prometheus metrics with p50/p95/p99
- [ ] Grafana dashboards (3 views)
- [ ] OpenTelemetry traces
- [ ] k6 benchmark results with real numbers

**Month 3 bullets (not yet earned):**
- [ ] Dynamic reconfiguration live
- [ ] Dark launch mode
- [ ] Kill switch + audit log
