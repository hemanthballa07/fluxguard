# ADR-001: Rate Limiting Algorithm Selection

**Date:** TBD
**Status:** Accepted

---

## Context
A distributed rate limiter must enforce per-client request limits across
multiple ECS instances sharing Redis. The algorithm must be correct under
concurrent access, use minimal Redis storage, and add sub-5ms overhead.

Five algorithms evaluated: fixed window, sliding window log,
sliding window counter, token bucket, leaky bucket.

## Decision
**Token bucket** as primary. **Sliding window counter** as secondary.
Switchable per-endpoint via LimitConfig — never hardcoded.

## Consequences
**Positive:** O(1) per request. Controlled burst. Industry standard (Stripe, AWS).
**Negative:** Token bucket allows brief bursts above nominal rate.
**Risks:** Clock skew affects refill timing — mitigated via ClockProvider + Redis server time.

## Alternatives considered
| Alternative        | Why rejected                                      |
|--------------------|---------------------------------------------------|
| Fixed window       | Double-capacity burst at window edges             |
| Sliding window log | O(R) memory per client — does not scale           |
| Leaky bucket       | No burst tolerance — too strict for API workloads |

## References
- Stripe engineering blog: token bucket for API rate limiting
- Cloudflare blog: sliding window counter at edge scale
