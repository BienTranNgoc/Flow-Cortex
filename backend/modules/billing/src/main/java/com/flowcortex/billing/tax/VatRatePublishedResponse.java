package com.flowcortex.billing.tax;

import com.flowcortex.billing.tax.VatRateTimelineResponse.VatRateView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.UUID;

/** The timeline after a rate was set, with the id of the row that was opened. */
@Schema(name = "VatRatePublishedResponse")
record VatRatePublishedResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String jurisdiction,
        @Schema(requiredMode = RequiredMode.REQUIRED) String category,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<VatRateView> rates,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID publishedRateId) {
}
