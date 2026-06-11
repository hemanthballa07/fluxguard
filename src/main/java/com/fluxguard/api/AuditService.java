package com.fluxguard.api;

import java.util.List;

/**
 * Records admin mutation events and provides a queryable audit log.
 *
 * <p>Implementations must be fail-open: a storage failure must not prevent
 * the admin operation from completing.
 */
public interface AuditService {

    /**
     * Records an admin mutation event.
     *
     * <p>Implementations must write a structured INFO log entry even if the
     * backing store is unavailable. The log entry is the durable source of truth.
     *
     * @param action  verb describing the operation (e.g. {@code "PUT_CONFIG"})
     * @param target  the resource affected (e.g. endpoint path, {@code "global"})
     * @param details human-readable summary of what changed
     * @param actor   identity of the caller; {@code "unknown"} when not set
     */
    void record(String action, String target, String details, String actor);

    /**
     * Returns the most recent audit entries, oldest first.
     *
     * <p>Returns at most {@code count} entries. If the backing store is unavailable,
     * returns an empty list rather than throwing.
     *
     * @param count maximum number of entries to return; must be positive
     * @return list of JSON audit strings, oldest first; never {@code null}
     */
    List<String> getRecent(int count);
}
