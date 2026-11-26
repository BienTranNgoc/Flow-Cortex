package com.flowcortex.billing.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowcortex.billing.catalog.CatalogueReader.CycleRow;
import com.flowcortex.billing.catalog.CatalogueReader.PlanRow;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** The tiers on sale, in order. Holds no version id, no timestamp and nothing that depends on the caller. */
record PlanListResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<PlanItem> data) {

    /** One tier with the cycles it is sold on; {@code cycles} is empty while no version is on sale. */
    record PlanItem(
            @Schema(requiredMode = RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String nameEn,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String taglineVi,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String taglineEn,
            @Schema(requiredMode = RequiredMode.REQUIRED) PlanMarketingFeatures marketingFeatures,
            @JsonProperty("isPopular") @Schema(requiredMode = RequiredMode.REQUIRED) boolean isPopular,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<PlanCycle> cycles) {
    }

    /** The marketing bullets in both languages. */
    record PlanMarketingFeatures(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> vi,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> en) {
    }

    /** One billing cycle of a tier: its price in whole đồng and the credits it includes per month. */
    record PlanCycle(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"monthly", "yearly"}) String billingCycle,
            @Schema(requiredMode = RequiredMode.REQUIRED) long priceVnd,
            @Schema(requiredMode = RequiredMode.REQUIRED) long includedCreditsPerMonth) {
    }

    static PlanListResponse from(List<PlanRow> rows) {
        return new PlanListResponse(rows.stream().map(PlanListResponse::item).toList());
    }

    private static PlanItem item(PlanRow row) {
        return new PlanItem(row.id(), row.name(), row.nameEn(), row.taglineVi(), row.taglineEn(),
                new PlanMarketingFeatures(row.marketing().vi(), row.marketing().en()), row.popular(),
                row.cycles().stream().map(PlanListResponse::cycle).toList());
    }

    private static PlanCycle cycle(CycleRow row) {
        return new PlanCycle(row.billingCycle(), row.priceVnd(), row.includedCreditsPerMonth());
    }
}
