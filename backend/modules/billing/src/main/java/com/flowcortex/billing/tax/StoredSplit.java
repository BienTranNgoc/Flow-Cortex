package com.flowcortex.billing.tax;

import java.util.UUID;

/** The tax split written onto a collected payment: the rate it was taken at and the rate row it was read from. */
public record StoredSplit(long baseVnd, long taxVnd, int rateBps, UUID rateId) {
}
