package com.fluxguard.exception;

/**
 * Thrown when the Redis backend is unavailable or returns an unexpected response.
 *
 * <p>This exception is unchecked so that callers are not forced to declare it in
 * signatures. {@code RateLimitFilter} catches it to implement fail-open behaviour:
 * log a warning, allow the request, and increment a metric counter. No other class
 * should swallow this exception silently.
 */
public class RedisUnavailableException extends RuntimeException {

    /**
     * Constructs a new exception with the given detail message.
     *
     * @param message human-readable description of the failure
     */
    public RedisUnavailableException(final String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given detail message and root cause.
     *
     * @param message human-readable description of the failure
     * @param cause   the underlying exception that triggered this failure
     */
    public RedisUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
