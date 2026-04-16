# SentinelRate — CLAUDE.md
> Project brain. Read this fully before every session.

## What this project is
Distributed rate limiter in Java 17 / Spring Boot 3.x.
Algorithms: token bucket + sliding window counter.
State: Redis cluster via Lua scripts (atomic, O(1) per request).
Deployed: AWS ECS/Fargate. CI/CD: GitHub Actions.
Observability: Prometheus + Grafana + OpenTelemetry + structured logs.
Month 3: Dynamic reconfiguration via feature flags (no redeployment).

## Always read PROJECT_STATE.md first
Before doing anything, read PROJECT_STATE.md to know:
- What phase we are in
- What was last completed
- What is in progress
- What is blocked
Update PROJECT_STATE.md after every meaningful change.

## Commands
```
mvn clean install                          # build
mvn test                                   # unit tests only
mvn verify                                 # unit + integration (Testcontainers)
mvn test -Dtest=TokenBucketTest            # single test class
docker-compose -f docker/docker-compose.yml up -d   # Redis + Grafana + Jaeger locally
docker-compose -f docker/docker-compose.yml down -v  # teardown + wipe volumes
./scripts/deploy.sh                        # build + push to ECR + deploy ECS
./k6/scripts/run-benchmark.sh             # load test + capture results
./scripts/chaos-redis.sh                  # kill Redis, verify fail-open
```

## Package structure
```
com.sentinelrate
├── algorithm/     # RateLimitAlgorithm interface + TokenBucket + SlidingWindow
├── config/        # LimitConfig, FeatureFlag, ConfigService, CaffeineCache
├── filter/        # RateLimitFilter (ONLY place that calls Redis)
├── redis/         # LuaScriptExecutor, RedisClientWrapper, scripts/*.lua
├── metrics/       # PrometheusMetricsCollector, counters, histograms
├── api/           # AdminController (config CRUD, flag management, kill switch)
├── model/         # Request, RateLimitDecision, ClientIdentity
├── exception/     # RateLimitException, RedisUnavailableException
└── util/          # HashUtil (for flag rollout), ClockProvider (testable)
```

## Architectural rules — never break these
- ALL Redis operations go through LuaScriptExecutor — never raw commands
- RateLimitFilter is the ONLY class that calls Redis directly
- Fail-open on ANY Redis exception — log warn, allow request, increment metric
- Redis timeout ceiling: 50ms — configure in application.yml
- Algorithm is selected per-endpoint from LimitConfig — never hardcoded
- Testcontainers for all integration tests — never mock Redis in integration tests
- ClockProvider wraps System.currentTimeMillis() — always inject, never call directly

## Code style
- Java 17 features: records, sealed interfaces, pattern matching where appropriate
- No Lombok — explicit getters/setters for clarity
- Javadoc on all public methods
- Max method length: 30 lines — extract if longer
- No magic numbers — constants in dedicated class or enum

## ADR rules
- Write ADR before any major architectural decision
- Format: /docs/adr/ADR-NNN-title.md
- Sections: Context | Decision | Consequences | Alternatives considered
- Link every ADR from README.md ADR index

## Testing rules
- Unit tests: pure Java, no Spring context, no Redis — fast
- Integration tests: Testcontainers, real Redis, full Spring context
- Every algorithm must have boundary condition tests (exact limit, limit+1, burst)
- k6 results must be captured in /k6/results/ after every benchmark run

## What Claude gets wrong on this project
(add mistakes here as they happen — one line each)
- (empty — populate as project progresses)
