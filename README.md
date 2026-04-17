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

| Algorithm              | Use case                | Storage | Burst        |
|------------------------|-------------------------|---------|--------------|
| Token bucket           | Default — API endpoints | O(1)    | Controlled   | 
| Sliding window counter | Strict endpoints        | O(1)    | Approximated |

## Observability

- Prometheus metrics: allowed/rejected counters, p50/p95/p99 latency
- Grafana dashboards: traffic overview, limiter internals, per-client breakdown
- OpenTelemetry traces: per-request span with throttle decision tags
- Structured JSON logs with trace ID correlation

## Benchmarks

> Run: 2026-04-16 — always_on tracing, single-node local Redis, loopback only.
> Production limits kept honest: `/api/search` 100 req/60s sliding window, `/api/ingest` 50-cap/10rps token bucket.
> See [`k6/results/`](k6/results/) for raw JSON summaries.

| Scenario         | RPS | p50    | p95    | p99    | 429 ratio | Notes                              |
|------------------|-----|--------|--------|--------|-----------|------------------------------------|
| steady-search    | 200 | 2.27ms | 4.26ms | 7.89ms | 79.6%     | sliding window, 20 clients         |
| steady-ingest    | 100 | 3.16ms | 5.02ms | 7.42ms | 0%        | token bucket at refill rate        |
| burst-ingest     | 255 | 2.03ms | 4.56ms | 8.02ms | 72.5%     | 50-token cap drained then refill   |
| threshold-search | 500 | 1.88ms | 4.44ms | 9.55ms | 99.4%     | single hot client, deny-path heavy |
| sustained-mixed  | 230 | 2.59ms | 4.83ms | 8.86ms | 45.5%     | 5 min, both endpoints              |

All thresholds passed. p99 under 10ms across all scenarios with full tracing enabled.

## Architecture Decision Records

| ADR                                             | Decision                               | Status   |
|-------------------------------------------------|----------------------------------------|----------|
| [ADR-001](docs/adr/ADR-001-algorithm-choice.md)       | Token bucket + sliding window selected  | Accepted |
| [ADR-002](docs/adr/ADR-002-data-store.md)             | Redis via ElastiCache                   | Accepted |
| [ADR-003](docs/adr/ADR-003-fail-open.md)              | Fail-open on Redis unavailability       | Accepted |
| [ADR-004](docs/adr/ADR-004-config-service-storage.md) | Redis hash as config source of truth    | Accepted |

## Local setup

```bash
docker-compose -f docker/docker-compose.yml up -d
mvn spring-boot:run
```

## Admin API

All `/admin/**` endpoints require the `X-Admin-Api-Key` header (set via `ADMIN_API_KEY` env var;
default `changeme-replace-in-prod`).

```bash
# 401 — missing key
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/admin/configs
# → 401

# 200 — list all rate-limit configs
curl -s -H "X-Admin-Api-Key: $ADMIN_API_KEY" http://localhost:8080/admin/configs
# → {"/api/search":{"endpointPattern":"/api/search","algorithm":{"type":"sliding_window","limit":100,"windowMs":60000}}, ...}

# PUT a feature flag with a live-rollout token-bucket override for 50% of clients
curl -s -X POST \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true,"darkLaunch":false,"rolloutPercent":50,"algorithm":"token_bucket","capacity":20,"refillRatePerSecond":5}' \
  "http://localhost:8080/admin/flags?endpoint=/api/search"
# → 200

# GET all feature flags
curl -s -H "X-Admin-Api-Key: $ADMIN_API_KEY" http://localhost:8080/admin/flags
# → {"/api/search":{"endpoint":"/api/search","enabled":true,"darkLaunch":false,"rolloutPercent":50, ...}}

# DELETE a feature flag
curl -s -X DELETE \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  "http://localhost:8080/admin/flags?endpoint=/api/search"
# → 204

# GET audit log (last 10 entries)
curl -s -H "X-Admin-Api-Key: $ADMIN_API_KEY" "http://localhost:8080/admin/audit?limit=10"
# → ["{\"action\":\"PUT_FLAG\",\"target\":\"/api/search\",...}", ...]
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
