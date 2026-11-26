package com.flowcortex.billing.entitlement;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/**
 * The plan a member holds now. {@code tierId} is {@code free} with {@code held} false when no paid plan is held,
 * and then the cycle, billing mode and period are null; {@code nextPlan} is the plan recorded for the next cycle
 * until its period begins.
 */
public record MemberPlanResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String tierId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String nameEn,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean held,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                allowableValues = {"monthly", "yearly"}) String billingCycle,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                allowableValues = {"manual", "auto_debit"}) String billingMode,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant startsAt,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant renewsAt,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) NextPlan nextPlan) {

    /** The plan that takes over when the held plan's period ends. */
    public record NextPlan(
            @Schema(requiredMode = RequiredMode.REQUIRED) String tierId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String nameEn,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"monthly", "yearly"}) String billingCycle,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant startsAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant renewsAt) {
    }
}
