package com.flowcortex.asset.domain;

import java.util.UUID;

/**
 * A claimed queue row. {@code leaseToken} fences the row: finishing or retrying with a token that
 * is no longer the row's changes nothing.
 */
public record RegistrationRow(
        UUID id,
        SourceType sourceType,
        UUID sourceId,
        UUID ownerId,
        RegistrationStatus status,
        int attempts,
        UUID leaseToken) {
}
