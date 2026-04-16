package com.fluxguard.exception;

/**
 * Thrown when a request is denied by the rate limiter.
 *
 * <p>This exception carries a {@code retryAfterMs} hint that the caller can use
 * to populate a {@code Retry-After} response header, telling the client when it
 * may safely retry. {@code RateLimitFilter} catches this exception and translates
 * it into an HTTP 429 response.
 */
public class RateLimitException extends RuntimeException {

    private final long retryAfterMs;

    /**
     * Constructs a new rate-limit exception.
     *
     * @param message      human-readable description of the denial reason
     * @param retryAfterMs milliseconds the client should wait before retrying
     */
    public RateLimitException(final String message, final long retryAfterMs) {
        super(message);
        this.retryAfterMs = retryAfterMs;
    }

    /**
     * Returns the number of milliseconds the client should wait before retrying.
     *
     * @return retry hint in milliseconds (non-negative)
     */
    public long retryAfterMs() {
        return retryAfterMs;
    }
}
