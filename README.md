# SentinelRate

Distributed rate limiter built in Java/Spring Boot.
Implements token bucket and sliding window counter algorithms,
backed by Redis Lua scripts for atomic O(1) per-request state updates.

## Architecture

```
Clients → ECS/Fargate (Spring Boot)
              ↓
        RateLimitFilter
              ↓
        LuaScriptExecutor → Redis Cluster (ElastiCache)
              ↓
        Business Logic
```

## Algorithms

| Algorithm | Use case | Storage | Burst |
|------------------------|----------|---------|-------|
| Token bucket           | Default — API endpoints | O(1) | Controlled |
| Sliding window counter | Strict endpoints | O(1) | Approximated |

## Observability

- Prometheus metrics: allowed/rejected counters, p50/p95/p99 latency
- Grafana dashboards: traffic overview, limiter internals, per-client breakdown
- OpenTelemetry traces: per-request span with throttle decision tags
- Structured JSON logs with trace ID correlation

## Benchmarks

> Updated after each k6 run. See /k6/results/ for raw data.

| Scenario | RPS | p50 | p95 | p99 |
|----------|-----|-----|-----|-----|
| (not yet run) | — | — | — | — |

## Architecture Decision Records

| ADR | Decision | Status |
|-----|----------|--------|
| [ADR-001](docs/adr/ADR-001-algorithm-choice.md) | Token bucket + sliding window selected | Accepted |
| [ADR-002](docs/adr/ADR-002-data-store.md) | Redis via ElastiCache | Accepted |
| [ADR-003](docs/adr/ADR-003-fail-open.md) | Fail-open on Redis unavailability | Accepted |

## Local setup

```bash
docker-compose -f docker/docker-compose.yml up -d
mvn spring-boot:run
```

## Running tests

```bash
mvn test           # unit tests
mvn verify         # unit + integration (Testcontainers)
./k6/scripts/run-benchmark.sh  # load test
./scripts/chaos-redis.sh       # fail-open verification
```

## Project state

See [PROJECT_STATE.md](PROJECT_STATE.md) for current progress,
session logs, and next pickup point.
