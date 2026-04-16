# Week 7 Codex Handoff

## Summary Of Work Completed
- Verified that `pom.xml` already contains the safe Week 7 test dependency for OpenTelemetry SDK testing.
- Verified that `src/main/resources/application.yml` already contains the safe Week 7 OTLP exporter and sampler configuration.
- Added this handoff note to document the safe config/documentation state before any production tracing code changes.

## Exact Files Changed
- `docs/week7-codex-handoff.md` (created)

## Exact Snippets Added
The following snippets were verified as already present and were not re-added:

### `pom.xml`
```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk-testing</artifactId>
    <scope>test</scope>
</dependency>
```

### `src/main/resources/application.yml`
```yaml
otel:
  exporter:
    otlp:
      endpoint: http://jaeger:4317
  traces:
    sampler: always_on
```

## Scope Confirmation
- Only config/documentation changes were made in this Codex pass.
- No production Java tracing logic was changed.
- No tracing tests were added or modified.

## Remaining For Claude Code
- LuaScriptExecutor tracing implementation
- RateLimitFilter tracing implementation
- DecisionOutcome `failOpenReason()` accessor if needed
- tracing tests
- `mvn compile` / `mvn test` / `mvn verify`
- Jaeger parent-child trace verification
