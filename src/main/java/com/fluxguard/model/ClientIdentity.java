package com.fluxguard.model;

/**
 * Identifies a rate-limit subject — a specific client accessing a specific endpoint.
 *
 * <p>The {@code bucketKey} is the Redis key that stores per-client state for
 * this endpoint. It is derived deterministically from {@code clientId} and
 * {@code endpoint} so that no external mapping is required.
 *
 * @param clientId  human-readable rate-limit subject (e.g. an API key prefix or IP)
 * @param endpoint  the HTTP path or logical resource being rate-limited
 * @param bucketKey Redis key for this client/endpoint combination
 */
public record ClientIdentity(String clientId, String endpoint, String bucketKey) {

    private static final String KEY_PREFIX = "rl:";
    private static final String KEY_SEPARATOR = ":";

    /**
     * Builds a {@link ClientIdentity} from a client identifier and endpoint.
     *
     * <p>The bucket key is constructed as {@code "rl:{clientId}:{endpoint}"},
     * keeping the namespace prefix {@code "rl:"} consistent across all keys.
     *
     * @param clientId the rate-limit subject identifier
     * @param endpoint the resource path being accessed
     * @return a fully populated {@code ClientIdentity}
     */
    public static ClientIdentity of(final String clientId, final String endpoint) {
        final String bucketKey = KEY_PREFIX + clientId + KEY_SEPARATOR + endpoint;
        return new ClientIdentity(clientId, endpoint, bucketKey);
    }
}
