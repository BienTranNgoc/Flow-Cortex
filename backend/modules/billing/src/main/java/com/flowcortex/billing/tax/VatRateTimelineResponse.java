package com.flowcortex.billing.tax;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The rate timeline of one jurisdiction and category, oldest first; {@code validTo} is exclusive. */
@Schema(name = "VatRateTimelineResponse")
record VatRateTimelineResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String jurisdiction,
        @Schema(requiredMode = RequiredMode.REQUIRED) String category,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<VatRateView> rates) {

    @Schema(name = "VatRateView")
    record VatRateView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) int rateBps,
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate validFrom,
            @Schema(nullable = true) LocalDate validTo,
            @Schema(nullable = true) String sourceNote) {

        static VatRateView of(VatRateEntry entry) {
            return new VatRateView(entry.id(), entry.rateBps(), entry.validFrom(), entry.validTo(),
                    entry.sourceNote());
        }
    }

    static VatRateTimelineResponse of(String jurisdiction, String category, List<VatRateEntry> entries) {
        return new VatRateTimelineResponse(jurisdiction, category, entries.stream().map(VatRateView::of).toList());
    }
}
