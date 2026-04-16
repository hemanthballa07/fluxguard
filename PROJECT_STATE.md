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
| 6 | Grafana dashboards | ✅ | `grafana/dashboards/*.json`, Grafana provisioning YAML |
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
- Created three Grafana dashboard JSON files in `grafana/dashboards/`
- Created Grafana dashboard provisioning YAML for folder `FluxGuard`
- Created Prometheus datasource provisioning YAML pointing to `http://prometheus:9090`
- Wired all dashboard queries to the exported FluxGuard metric families with `application="fluxguard"`
- Fixed: pinned `docker-compose.yml` Grafana image to `grafana/grafana:11.4.0` — Grafana 13 (`latest`) uses unified storage (mode 5) which breaks the old `/api/dashboards/uid/:uid` REST endpoint; 11.4.0 uses standard file provisioning and the old API works correctly
- Verified: all three dashboard titles return via `/api/dashboards/uid/` and datasource proxy returns `"status":"success"`

**Files changed:**
- `grafana/dashboards/traffic-overview.json`
- `grafana/dashboards/limiter-internals.json`
- `grafana/dashboards/per-client.json`
- `grafana/provisioning/dashboards/dashboards.yaml`
- `grafana/provisioning/datasources/prometheus.yaml`
- `docker/docker-compose.yml` (pinned grafana image to 11.4.0)
- `PROJECT_STATE.md`, `CHANGELOG.md`

**Decisions made:**
- Dashboard JSON schema version set to `36` with `timeseries` panels only
- Provisioned Prometheus as the default Grafana datasource with uid `prometheus`
- Dashboard provider folder set to `FluxGuard` and file path set to `/var/lib/grafana/dashboards`
- Grafana pinned to `11.4.0` not `latest` — Grafana 13 unified storage breaks `type: file` provisioning compatibility with the old REST API; 11.x is LTS-stable and retains full old-API support

**Tests status:**
- All three dashboard UIDs resolve correctly: `FluxGuard Traffic Overview`, `FluxGuard Limiter Internals`, `FluxGuard Per Client`
- Datasource proxy query `rate_limit_allowed_total` returns `"status":"success"`
- 20 warm-up requests sent; metric data flows through Prometheus to Grafana panels

---

## Current session (in progress)
**Started:** —
**Working on:** —
**Blockers:** none

---

## Next session pickup
**Start here:** Week 7 — OpenTelemetry tracing
**First task:** Add request and Redis tracing spans/tags so Grafana/Jaeger can correlate limiter decisions with endpoint, algorithm, result, and Redis script execution. Dashboard and provisioning files are now present under `grafana/`.
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
| Grafana dashboards | `grafana/dashboards/` | ✅ Built | — | Three dashboards: traffic overview, limiter internals, per-client breakdown |
| Grafana provisioning | `grafana/provisioning/` | ✅ Built | — | File-based provider + default Prometheus datasource |
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
- [x] Grafana dashboards (3 views)
- [ ] OpenTelemetry traces
- [ ] k6 benchmark results with real numbers

**Month 3 bullets (not yet earned):**
- [ ] Dynamic reconfiguration live
- [ ] Dark launch mode
- [ ] Kill switch + audit log
