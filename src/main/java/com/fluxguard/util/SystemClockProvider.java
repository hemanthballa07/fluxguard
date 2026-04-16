package com.fluxguard.util;

import org.springframework.stereotype.Component;

/**
 * Production {@link ClockProvider} that delegates to
 * {@link System#currentTimeMillis()}.
 *
 * <p>Registered as a Spring bean so it can be injected wherever a
 * {@code ClockProvider} is required. Tests supply a stub instead.
 */
@Component
public class SystemClockProvider implements ClockProvider {

    /**
     * {@inheritDoc}
     */
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
