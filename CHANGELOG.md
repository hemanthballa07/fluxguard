# CHANGELOG.md
> Append-only log of every meaningful change.
> Never delete entries. Newest at top.
> Format: ## [date] — [week] — [what changed]

---

## [2026-04-16] — Week 7 — OTLP gRPC fix + log pattern correction
- `application.yml` — added `protocol: grpc` under `otel.exporter.otlp`; OTel starter requires explicit protocol to use gRPC (port 4317) instead of defaulting to http/protobuf (port 4318)
- `application.yml` — fixed log pattern MDC keys: `%X{traceId}` → `%X{trace_id}`, `%X{spanId}` → `%X{span_id}`; OTel uses snake_case MDC keys, not Micrometer-style camelCase
- Jaeger E2E verification passed: `rate_limit.decision` and `redis.lua_script` spans confirmed for service `fluxguard`
- Known gap logged: log `trace_id=` remains empty — `opentelemetry-logback-mdc-1.0` MDC bridge dep + `logback-spring.xml` needed; deferred to Month 2 cleanup
- Test counts corrected: 90 total (83 unit + 7 IT)

## [2026-04-16] — Week 1 — pom.xml resolved clean
- `pom.xml` re-initialized: Spring Boot 3.4.1 parent, `opentelemetry-instrumentation-bom` 2.25.0 imported explicitly, Resilience4j 2.3.0 pinned, Testcontainers 1.21.4, JaCoCo 0.8.14, Checkstyle 3.6.0
- `checkstyle/checkstyle.xml` scaffolded with CLAUDE.md rules (Javadoc on public methods, max 30-line methods, no magic numbers)
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
- All 5 Month 1 resume bullets earned
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
- CLAUDE.md written
- PROJECT_STATE.md initialized
- CHANGELOG.md initialized
