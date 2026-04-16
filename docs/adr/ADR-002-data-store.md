# ADR-002: Data Store for Rate Limit State

**Date:** TBD
**Status:** Accepted

---

## Context
Counters must be shared across all ECS task instances.
Must support atomic increments, TTL expiry, sub-10ms p99.

## Decision
**Redis** (AWS ElastiCache) with **Lua scripts** for all atomic ops.

## Consequences
**Positive:** Sub-ms ops. Native INCR + TTL + Lua. Battle-tested at scale.
**Negative:** Ephemeral — counter loss on node restart (acceptable for short windows).
**Risks:** Redis outage blocks requests without fail-open — mitigated by ADR-003.

## Alternatives considered
| Alternative | Why rejected |
|-------------|-------------|
| DynamoDB | 10-20ms latency — too slow per-request |
| Local in-memory | No shared state across ECS instances |
| Memcached | No Lua scripting, no replication |
