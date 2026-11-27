package com.flowcortex.billing.profile;

import java.time.Instant;
import java.util.UUID;

/** A member's invoice details as stored. */
public record BillingProfile(UUID id, String type, String legalName, String taxCode, String personalIdNumber,
        String address, String email, boolean isDefault, Instant createdAt, Instant updatedAt) {
}
