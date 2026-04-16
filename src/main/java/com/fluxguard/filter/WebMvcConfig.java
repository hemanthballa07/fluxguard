package com.fluxguard.filter;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers Spring MVC interceptors for the application.
 *
 * <p>{@link RateLimitFilter} is applied to all request paths. Path exclusion
 * is handled inside the filter itself: requests to unknown paths are passed
 * through without calling Redis.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitFilter rateLimitFilter;

    /**
     * Constructs the MVC configurer with the rate-limit interceptor.
     *
     * @param rateLimitFilter the interceptor to register
     */
    public WebMvcConfig(final RateLimitFilter rateLimitFilter) {
        this.rateLimitFilter = rateLimitFilter;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Registers {@link RateLimitFilter} for all paths. The filter skips
     * rate limiting internally for paths not present in its configuration map.
     */
    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitFilter);
    }
}
