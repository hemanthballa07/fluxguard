package com.fluxguard.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces API-key authentication on all {@code /admin/**} endpoints.
 *
 * <p>Callers must include the {@code X-Admin-Api-Key} header with the correct key.
 * On success, the request attribute {@code X-Admin-Actor} is set to {@code "admin"}.
 * On failure, the response is committed with HTTP 401 and the request is rejected.
 *
 * <p>A single static key is used — there is no per-key identity mapping.
 * The actor label is always {@code "admin"}.
 *
 * <p>The expected key is injected via constructor so that this class remains
 * testable without a Spring context. {@code @Value} resolution lives exclusively
 * at the {@code @Bean} site in {@code RateLimitConfiguration}.
 *
 * <p>This class carries no {@code @Component} annotation; it is registered
 * exclusively via {@code @Bean} in {@code RateLimitConfiguration}.
 */
public class AdminAuthFilter implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(AdminAuthFilter.class);

    private static final String HEADER_API_KEY      = "X-Admin-Api-Key";
    private static final String ACTOR_ATTR          = "X-Admin-Actor";
    private static final String ACTOR_VALUE         = "admin";
    private static final int    STATUS_UNAUTHORIZED = 401;

    private final String expectedApiKey;

    /**
     * Constructs the filter with the expected API key.
     *
     * @param expectedApiKey the secret key callers must present; must not be null or blank
     */
    public AdminAuthFilter(final String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
    }

    /**
     * Validates the {@code X-Admin-Api-Key} header.
     *
     * @param request  current HTTP request
     * @param response current HTTP response
     * @param handler  chosen handler (unused)
     * @return {@code true} to proceed; {@code false} if key is missing or wrong (401 committed)
     */
    @Override
    public boolean preHandle(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final Object handler) {
        final String key = request.getHeader(HEADER_API_KEY);
        if (key == null || key.isBlank() || !key.equals(expectedApiKey)) {
            LOG.warn("Admin request rejected — missing or invalid {}", HEADER_API_KEY);
            response.setStatus(STATUS_UNAUTHORIZED);
            return false;
        }
        request.setAttribute(ACTOR_ATTR, ACTOR_VALUE);
        return true;
    }
}
