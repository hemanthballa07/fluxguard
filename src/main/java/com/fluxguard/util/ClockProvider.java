package com.fluxguard.util;

/**
 * Abstraction over system time so that every time-dependent class is testable
 * without {@link Thread#sleep} or fragile wall-clock assertions.
 *
 * <p>Always inject this interface — never call {@link System#currentTimeMillis()}
 * directly. Tests replace it with a controllable stub.
 */
public interface ClockProvider {

    /**
     * Returns the current time in milliseconds since the Unix epoch.
     *
     * @return current epoch time in milliseconds, always positive
     */
    long nowMillis();
}
