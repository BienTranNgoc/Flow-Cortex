package com.flowcortex.billing.region;

import java.util.Locale;

/** Where the registries place an address; {@code UNKNOWN} when it cannot be placed at all. */
public enum RegionVerdict {
    VN,
    FOREIGN,
    UNKNOWN;

    /** Lower-case form used in tags and logs. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
