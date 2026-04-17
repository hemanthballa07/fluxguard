package com.fluxguard.filter;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers Spring MVC interceptors for the application.
 *
 * <p>{@link RateLimitFilter} is applied to all request paths ({@code /**}).
 * Path exclusion is handled inside the filter: requests to unknown paths pass
 * through without calling Redis.
 *
 * <p>{@link AdminAuthFilter} is applied to {@code /admin/**} only and runs after
 * {@link RateLimitFilter} (registration order). Admin paths pass through
 * {@link RateLimitFilter} immediately because no {@link com.fluxguard.config.LimitConfig}
 * is registered for them.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitFilter rateLimitFilter;
    private final AdminAuthFilter adminAuthFilter;

    /**
     * Constructs the MVC configurer with both interceptors.
     *
     * @param rateLimitFilter the rate-limit interceptor registered for all paths
     * @param adminAuthFilter the API-key interceptor registered for {@code /admin/**} only
     */
    public WebMvcConfig(
            final RateLimitFilter rateLimitFilter,
            final AdminAuthFilter adminAuthFilter) {
        this.rateLimitFilter = rateLimitFilter;
        this.adminAuthFilter = adminAuthFilter;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Registers {@link RateLimitFilter} for all paths, then {@link AdminAuthFilter}
     * scoped to {@code /admin/**}. Interceptors execute in registration order.
     */
    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitFilter);
        registry.addInterceptor(adminAuthFilter).addPathPatterns("/admin/**");
    }
}
