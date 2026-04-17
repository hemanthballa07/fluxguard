# ADR-004: Dynamic Config Storage — Redis as Source of Truth

**Date:** 2026-04-16
**Status:** Accepted

---

## Context

Month 3 introduces runtime reconfiguration: rate-limit parameters must be changeable
without redeploying the service. The app runs on AWS ECS/Fargate with multiple task
instances behind a load balancer. Any storage strategy must deliver consistent config
across all instances.

Three options were evaluated: in-memory (per-instance), Redis (shared cluster already
present), and an external relational database (no infra exists yet).

The existing architectural rule — "RateLimitFilter is the ONLY class that calls Redis" —
was written in Month 1 when rate-limiting was the sole Redis use case. That rule requires
amendment now that config management is a second use case.

---

## Decision

**Redis hash as the source of truth for rate-limit configs.**

- Configs stored as flat JSON strings in a single Redis hash key (`fluxguard:configs`).
  Hash field = endpoint path; hash value = JSON-serialised algorithm parameters.
- Kill switch stored as a plain Redis key (`fluxguard:kill-switch`).
- `RedisConfigService` reads and writes via `StringRedisTemplate` directly
  (no Lua scripts — config CRUD is non-atomic single-key operations; Lua exists
  solely for atomic counter operations in rate-limiting decisions).
- On startup, a `CommandLineRunner` seeds Redis from the static `LimitConfig` bean
  map, skipping any keys already present (idempotent across restarts).
- **Rule amendment:** `ConfigService` joins `RateLimitFilter` as a Redis caller.
  The amended rule: "ALL Redis operations for rate-limiting decisions go through
  `LuaScriptExecutor`. Config CRUD uses `StringRedisTemplate` via `RedisConfigService`."

---

## Consequences

**Positive:**
- Config updates are immediately visible to all ECS instances — no cross-instance lag.
- Redis infrastructure is already in place; no new dependencies.
- `ConfigService` is an interface: `RedisConfigService` can be swapped or extended
  without touching the filter or admin layer.

**Negative:**
- Config reads add a Redis round-trip per `getConfig()` call (~1–5 ms within VPC).
  A Caffeine cache with short TTL can be added in Week 10 if latency becomes a
  problem under high admin-API traffic.
- Malformed Redis entries (corrupted JSON, unknown algorithm) are silently skipped
  with a WARN log — operators must monitor logs after manual Redis edits.

**Risks:**
- If Redis is unavailable during startup seeding, the app starts with an empty
  config map (no endpoints rate-limited). Existing circuit-breaker fail-open logic
  in `RateLimitFilter` already handles this gracefully.

---

## Alternatives considered

| Alternative | Why rejected |
|-------------|-------------|
| In-memory `ConcurrentHashMap` | Per-instance — updates on one pod are invisible to others. Unacceptable for ECS multi-instance. |
| External relational DB (RDS) | No infrastructure exists; adds new dependency and operational burden. Overkill for key-value config. |
| In-memory Week 9 then Redis Week 10 | Throwaway abstraction. The interface is identical; build Redis-backed from day one. |
| Lua scripts for config CRUD | Lua scripts provide atomicity for counters. Config reads/writes are simple GET/SET — forcing them through Lua adds complexity without benefit. |

---

## References

- ADR-002: Data store choice (Redis + Lua for rate-limit counters)
- ADR-003: Fail-open on Redis unavailability
