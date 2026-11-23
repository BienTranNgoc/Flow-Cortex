package com.flowcortex.asset.domain;

/** Where a queue row stands; {@code PENDING} rows are the ones a claim can take. */
public enum RegistrationStatus {
    PENDING,
    DONE,
    SKIPPED,
    STUCK;

    /** The value stored in {@code space_asset_registration.status}. */
    public String column() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static RegistrationStatus fromColumn(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
