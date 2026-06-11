# ADR-008 — Outcome-aware login rate limiting

**Status:** Accepted
**Date:** 2026-06-02
**Related:** ADR-001 (sliding window), ADR-007 (gRPC surface); spec `docs/specs/2026-06-02-bankops-ratelimit-grpc-integration.md` (§D3, D4)

## Context

Phase 2 protects bankops's login against brute-force over the gRPC surface. The complication: bankops has **no `/login`** — credentials are validated by `GET /api/whoami`, which is **dual-purpose**: it is both the login credential check *and* the session-hydration call fired on every app load and per browser tab. A synchronous pre-check cannot see the `401` outcome. So a naive "rate-limit every `/whoami`" either throttles legitimate hydration or, tuned loose enough not to, provides no real brute-force protection. bankops explicitly flagged the IP-counts-everything approach as weak and offered to report auth failures.

## Decision

1. **Count failures, not traffic (outcome-aware).** `CheckLimit(LOGIN)` is a **read-only** check of a recent-failure window; bankops calls a new **`ReportLoginFailure`** RPC after a `401`, which is the only thing that increments the window. Successful logins and hydration traffic never touch it, so the window reflects actual failed attempts.

2. **Key the failure window on `client_ip` only — never username.** A username-keyed failure window lets an attacker lock a victim out of login by spraying failures for that username (account-lockout DoS). IP-keying avoids this. Because only *failures* count, a busy legitimate IP (whose hydration succeeds) accrues nothing — so IP-keying here does not throttle normal use, which was the original objection to IP-counting. `subject` (attempted username) is carried for **audit only**.

3. **Split the sliding-window Lua into read and write halves.** The existing `sliding_window.lua` conflates check + increment (it `INCR`s on every call), which is wrong for a read-only pre-check. Phase 2 adds `sliding_window_peek.lua` (weighted count, no write) for `CheckLimit(LOGIN)` and `sliding_window_incr.lua` (increment + expire) for `ReportLoginFailure`. The token-bucket path is unaffected.

4. **Fail open** (consistent with ADR-003): a Redis failure on the peek allows the login attempt; a failure on the increment is swallowed (an uncounted failure is the accepted cost).

## Consequences

- bankops gains a second, asymmetric integration point on the auth path (a post-`401` `ReportLoginFailure` call via an `AuthenticationFailureHandler` or the `/whoami` 401 site) — but it is off the transaction commit path, non-blocking, and fail-open, so it never affects latency or correctness of the commit flow.
- `client_ip` must be derived by bankops from the real socket peer or a trusted proxy, **not** a spoofable `X-Forwarded-For`, or the window is bypassable. Recorded as a spec requirement.
- During an active attack from one IP, that IP's legitimate hydration is also throttled — acceptable collateral, since the failures are originating from there.

## Alternatives considered

- **IP-keyed window counting every `/whoami` (bankops Q2 option i):** one call, no post-auth wiring, but bankops's own analysis showed it is weak — hydration and brute-force share one budget; the window must be tuned so loose it barely protects. Rejected.
- **Username-keyed failure window:** tighter per-account protection, but opens an account-lockout DoS where an attacker locks out a victim. Rejected for IP-only.
- **Defer login limiting entirely:** ships the transaction MVP faster but drops a requested feature and leaves the sliding-window algorithm unexercised by the integration. Rejected — done as Phase 2 instead.
