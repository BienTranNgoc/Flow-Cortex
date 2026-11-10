package com.flowcortex.billing.region;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What the payments region gate does: {@code off} places nothing, {@code shadow} only measures, {@code enforce} refuses. */
public enum PaymentsRegionGateMode {
    OFF("off"),
    SHADOW("shadow"),
    ENFORCE("enforce");

    private static final Logger LOG = LoggerFactory.getLogger(PaymentsRegionGateMode.class);

    private final String value;

    PaymentsRegionGateMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** The mode named by {@code raw}; an absent or unrecognised value reads as shadow, the unrecognised non-empty ones with a WARN. */
    public static PaymentsRegionGateMode parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return SHADOW;
        }
        for (PaymentsRegionGateMode mode : values()) {
            if (mode.value.equals(raw)) {
                return mode;
            }
        }
        LOG.warn("Unrecognised payments region gate mode '{}', reading it as shadow", raw);
        return SHADOW;
    }

    /** Whether a caller with this verdict may proceed. */
    public boolean allows(RegionVerdict verdict) {
        return this != ENFORCE || verdict != RegionVerdict.FOREIGN;
    }
}
