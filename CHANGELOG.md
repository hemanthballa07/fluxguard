# CHANGELOG.md
> Append-only log of every meaningful change.
> Never delete entries. Newest at top.
> Format: ## [date] — [week] — [what changed]

---

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
