# ADR-006: Admin API Authentication and Audit Log

**Date:** 2026-04-17
**Status:** Accepted

---

## Context

The `/admin/**` endpoints (`PUT/DELETE /admin/configs`, `POST /admin/kill-switch/*`,
`GET/PUT/DELETE /admin/flags`) mutate global rate-limit state that affects all
traffic across all instances. Before Week 11 these endpoints were unauthenticated:
any caller on the network could activate the kill switch or overwrite limits.

Two requirements drive this decision:

1. **Authentication** — only authorised operators should be able to call admin endpoints.
2. **Audit trail** — every mutation must be recorded for accountability and incident response.

The team is a single internal operator group. There is no multi-tenant or
per-user identity requirement at this stage.

---

## Decision

**Authentication:** A single static API key enforced via the `X-Admin-Api-Key`
HTTP request header. `AdminAuthFilter` (a `HandlerInterceptor`) rejects requests
with a missing or mismatched key with HTTP 401. On success it sets the request
attribute `X-Admin-Actor = "admin"`. The key is loaded from the environment
variable `ADMIN_API_KEY` (fallback: `changeme-replace-in-prod`).

**Audit log:** Every successful admin mutation is dual-written:

1. A structured INFO log entry via the dedicated `audit.admin` SLF4J logger —
   always fires, even on Redis failure. This is the durable source of truth.
2. A JSON entry `RPUSH`-ed to the Redis list `fluxguard:audit-log`, capped at
   10 000 entries via `LTRIM`. Queryable via `GET /admin/audit`.

Redis audit writes are fail-open: a `RuntimeException` is caught, logged at WARN,
and swallowed. Admin operations complete regardless of Redis availability.

The API key is injected into `AdminAuthFilter` via constructor (not `@Value` on the
class) so the filter is testable without a Spring context. `@Value` resolution lives
exclusively at the `@Bean` site in `RateLimitConfiguration`.

---

## Consequences

**Positive:**
- Admin surface is protected with zero additional infrastructure (no auth server, no JWT library).
- Every mutation has a durable structured log line regardless of Redis state.
- `GET /admin/audit` gives operators a queryable recent history without requiring log aggregation.
- `AdminAuthFilter` is pure Java — 4 unit tests, no Spring context required.

**Negative:**
- Single key = no per-operator identity. All mutations show actor `"admin"`.
- Key rotation requires a redeployment or ECS task restart (environment variable change).
- Redis list is not atomic with the log write — under concurrent mutations the list
  may briefly exceed 10 000 entries before `LTRIM` fires (bounded overshoot only).

**Risks:**
- The default key (`changeme-replace-in-prod`) will be used if `ADMIN_API_KEY` is not
  set in the ECS task definition. Operators must set this env var before production deployment.

---

## Alternatives considered

| Alternative | Why rejected |
|-------------|-------------|
| Spring Security with JWT | Overkill for a single-team internal surface; adds a token-issuance dependency |
| Per-operator key map in Redis | Adds runtime complexity and a bootstrap problem (who creates the first key?); not needed at current team size |
| Database audit table | No RDBMS in the stack; adding one for audit alone is disproportionate |
| Redis-only audit (no SLF4J log) | Redis is not durable by default; a Redis restart would lose the audit log entirely |
| SLF4J-only audit (no Redis list) | No queryable API without a log aggregation pipeline; `GET /admin/audit` would not be possible |

## References

- ADR-003: Fail-open on Redis unavailability — audit Redis writes follow the same posture
- `AdminAuthFilter.java` — implementation
- `RedisAuditService.java` — implementation
