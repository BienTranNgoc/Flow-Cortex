package com.flowcortex.billing.tax;

import java.util.UUID;

/** The one VAT rate row in force on a day. */
public record ResolvedRate(UUID id, int rateBps) {
}
