package com.flowcortex.billing.tax;

import java.util.List;
import java.util.UUID;

/** The row a publish opened and the timeline as it stood when the publish committed, oldest first. */
public record PublishedVatRate(UUID publishedRateId, List<VatRateEntry> rates) {
}
