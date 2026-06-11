package com.fluxguard.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for creating or updating a per-endpoint rate-limit config.
 *
 * <p>Field semantics by algorithm:
 * <ul>
 *   <li>{@code token_bucket}: requires {@code capacity} and {@code refillRatePerSecond}.</li>
 *   <li>{@code sliding_window}: requires {@code limit} and {@code windowMs}.</li>
 * </ul>
 *
 * <p>{@code AdminController} validates which fields are present for the chosen algorithm
 * and rejects the request with 400 if required fields are null or non-positive.
 */
public record LimitConfigRequest(
        @NotBlank String algorithm,
        Long capacity,
        Long refillRatePerSecond,
        Long limit,
        Long windowMs) {
}
