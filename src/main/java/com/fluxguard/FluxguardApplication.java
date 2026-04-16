package com.fluxguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the FluxGuard distributed rate limiter.
 *
 * <p>FluxGuard enforces per-client request quotas using a token-bucket or
 * sliding-window algorithm backed by Redis Lua scripts for atomic, O(1)
 * state updates. The service fails open on any Redis unavailability so that
 * downstream traffic is never blocked by an infrastructure fault.
 */
@SpringBootApplication
public class FluxguardApplication {

    /**
     * Launches the Spring Boot application context.
     *
     * @param args command-line arguments forwarded to Spring
     */
    public static void main(final String[] args) {
        SpringApplication.run(FluxguardApplication.class, args);
    }
}
