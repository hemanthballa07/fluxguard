package com.fluxguard.engine;

import com.fluxguard.model.RateLimitDecision;

/**
 * Carries a rate-limit decision together with the metrics labels derived from it.
 *
 * @param decision       the underlying allow/deny decision
 * @param resultLabel    one of {@code allowed}, {@code denied}, or {@code failopen}
 * @param failOpenReason the fail-open trigger reason, or {@code null} when not failing open
 */
public record DecisionOutcome(
        RateLimitDecision decision,
        String resultLabel,
        String failOpenReason) {
}
