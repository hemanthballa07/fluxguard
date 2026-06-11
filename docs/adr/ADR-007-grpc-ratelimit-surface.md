# ADR-007 — Synchronous gRPC rate-limit surface

**Status:** Accepted
**Date:** 2026-06-02
**Related:** ADR-003 (fail-open), ADR-004 (config storage); spec `docs/specs/2026-06-02-bankops-ratelimit-grpc-integration.md`

## Context

fluxguard has been an HTTP-filter-only rate limiter (`RateLimitFilter`, a Spring MVC `HandlerInterceptor`) over a token-bucket / sliding-window + Redis-Lua + Resilience4j fail-open core. The trifecta integration requires bankops-portal to call fluxguard **synchronously before committing a transaction** (and before its fraud-eval call), mirroring how bankops already calls fluxa's fraud-eval over gRPC (`FluxaFraudClient` → `fluxa.fraud.v1.FraudEval`). This needs a gRPC surface fluxguard does not yet have, and it must reuse — not duplicate — the existing decision core, fail-open posture, and OpenTelemetry story (shared Jaeger).

## Decision

1. **Add a synchronous gRPC service** `fluxguard.ratelimit.v1.RateLimit` with `CheckLimit` (used pre-commit) and `ReportLoginFailure` (Phase 2, login brute-force). Callers fail-open on deadline/error; fluxguard fails-open internally on Redis-down (`fail_open=true` in the response), consistent with ADR-003.
2. **Use raw grpc-java** (`grpc-netty-shaded`) with a `SmartLifecycle`-managed `Server` bean on **`:9099`**, plus gRPC health + reflection, and the OpenTelemetry `GrpcTelemetry` server interceptor (symmetric twin of bankops's client interceptor) so a request joins the shared-Jaeger trace. Chosen over `grpc-spring-boot-starter` / `spring-grpc` to match the house style (explicit beans, no Lombok, no framework magic) and to mirror fluxa's own manual gRPC server.
3. **Extract a shared `RateLimitEngine`** from `RateLimitFilter` so the HTTP filter and the gRPC service share one fail-open / circuit-breaker / `rate_limit.decision` span path. Feature flags, dark launch, and HTTP headers stay filter-side; the engine owns only `(LimitConfig, bucketKey) → decision`.
4. **Decouple from bankops URLs via a `Policy` enum** (TRANSACTION / OPS_RELEASE / OPS_REJECT / LOGIN) mapped to admin-tunable `LimitConfig`s, since the bankops route carries a templated `{accountId}` that must not be the bucket key.

## Consequences

- New build complexity: `protobuf-maven-plugin` codegen + grpc-java deps; `grpc-netty-shaded` avoids a Netty clash with Spring Boot. The OTel gRPC instrumentation is an `-alpha` artifact, so the alpha instrumentation BOM is imported.
- Two server lifecycles (servlet `:8080`, gRPC `:9099`); the gRPC server drains in-flight calls on shutdown.
- The decision-core extraction is a behavior-preserving refactor of the most load-bearing, fully-tested class; existing filter test assertions are unchanged (only their construction is rewired to inject the engine).
- Idempotency: a replayed `Idempotency-Key` returns the prior decision without spending a token (Redis dedupe cache, fail-open).

## Alternatives considered

- **`grpc-spring-boot-starter` (net.devh):** least boilerplate, but a heavyweight third-party starter with its own magic — against the house style — and it obscures the explicit, symmetric OTel interceptor wiring. Rejected.
- **Spring's official `spring-grpc`:** the long-term Spring answer, but young (2024+) and version-immature for a portfolio piece. Rejected.
- **In-process Spring filter / reverse-proxy gateway in bankops (bankops Q1 options b/c):** breaks trifecta symmetry with the fraud-eval gRPC call and moves enforcement off the synchronous pre-commit path bankops owns. Rejected in favor of the synchronous gRPC check.
- **Keep HTTP-only and have bankops call fluxguard over REST:** loses the shared-trace gRPC-metadata propagation and the symmetry with fluxa. Rejected.
