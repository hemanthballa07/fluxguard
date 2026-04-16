# ADR-003: Fail-Open on Redis Unavailability

**Date:** TBD
**Status:** Accepted

---

## Context
If Redis is unreachable or slow (>50ms), the limiter must choose:
block all requests (fail-closed) or allow all (fail-open).

## Decision
**Fail-open**: allow request, log warning, increment redis_failopen_total counter.
Redis timeout ceiling: 50ms. Resilience4j circuit breaker wraps all Redis calls.

## Consequences
**Positive:** API stays available during Redis incidents. Matches Stripe production practice.
**Negative:** Rate limiting unenforced during outage — brief abuse window accepted.
**Risks:** Sustained outage = sustained unenforced limits. Alert on redis_failopen_total spike.

## Alternatives considered
| Alternative | Why rejected |
|-------------|-------------|
| Fail-closed | Full API outage on Redis failure — unacceptable |
| Local fallback counter | State inconsistency across instances |

## References
- Stripe: "exceptions fail open so the API stays functional"
