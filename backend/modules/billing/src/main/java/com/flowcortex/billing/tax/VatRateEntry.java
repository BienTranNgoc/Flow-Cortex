package com.flowcortex.billing.tax;

import java.time.LocalDate;
import java.util.UUID;

/** One row of the timeline; {@code validTo} is exclusive and null when open-ended. */
public record VatRateEntry(UUID id, int rateBps, LocalDate validFrom, LocalDate validTo, String sourceNote) {
}
