package com.flowcortex.billing.profile;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** One of the member's billing profiles. */
@Schema(name = "BillingProfileResponse")
record BillingProfileResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"personal", "business", "household"})
        String type,
        @Schema(requiredMode = RequiredMode.REQUIRED) String legalName,
        @Schema(nullable = true) String taxCode,
        @Schema(nullable = true) String personalIdNumber,
        @Schema(requiredMode = RequiredMode.REQUIRED) String address,
        @Schema(nullable = true) String email,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean isDefault,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

    static BillingProfileResponse from(BillingProfile profile) {
        return new BillingProfileResponse(profile.id(), profile.type(), profile.legalName(), profile.taxCode(),
                profile.personalIdNumber(), profile.address(), profile.email(), profile.isDefault(),
                profile.createdAt(), profile.updatedAt());
    }

    /** {@code profile} is null when the member has no default. */
    @Schema(name = "DefaultBillingProfileResponse")
    record Default(@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) BillingProfileResponse profile) {
    }

    /**
     * Shape of the create and edit bodies, for the contract only: the controller reads the body as a tree so
     * an absent field differs from {@code null}.
     */
    @Schema(name = "BillingProfileRequest")
    record Request(
            @Schema(allowableValues = {"personal", "business", "household"}) String type,
            @Schema(maxLength = 400) String legalName,
            @Schema(nullable = true, description = "business and household: 10 digits, optionally -3 digits")
            String taxCode,
            @Schema(nullable = true, description = "personal: 12 digits") String personalIdNumber,
            @Schema(maxLength = 500) String address,
            @Schema(nullable = true, maxLength = 255) String email,
            @Schema(description = "true makes this the default; false and absence change nothing") Boolean isDefault) {
    }
}
