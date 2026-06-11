package com.fluxguard.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trivial endpoints used only during k6 load tests.
 *
 * <p>Enabled only under the {@code benchmark} Spring profile so stub handlers
 * never ship to production. Each handler returns a fixed short body and does
 * zero work, so k6 measurements reflect pure rate-limit overhead
 * ({@code RateLimitFilter} + {@code LuaScriptExecutor} + Redis) rather than
 * business logic.
 *
 * <p>The two URIs match the production rate-limit map declared in
 * {@code RateLimitConfiguration}:
 * <ul>
 *   <li>{@code GET  /api/search}  — 100 req / 60 s sliding window</li>
 *   <li>{@code POST /api/ingest}  — token bucket: capacity 50, refill 10/s</li>
 * </ul>
 */
@RestController
@Profile("benchmark")
public class BenchmarkController {

    /** Fixed allowed-response body; tiny so k6 does not measure serialization. */
    private static final String OK_BODY = "ok";

    /**
     * Handles load-test requests against the sliding-window-limited search endpoint.
     *
     * @return fixed "ok" body; only reached when the rate-limit filter allows the request
     */
    @GetMapping(value = "/api/search", produces = MediaType.TEXT_PLAIN_VALUE)
    public String search() {
        return OK_BODY;
    }

    /**
     * Handles load-test requests against the token-bucket-limited ingest endpoint.
     *
     * @return fixed "ok" body; only reached when the rate-limit filter allows the request
     */
    @PostMapping(value = "/api/ingest", produces = MediaType.TEXT_PLAIN_VALUE)
    public String ingest() {
        return OK_BODY;
    }
}
