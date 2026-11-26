package com.flowcortex.asset.domain;

import java.util.Locale;

/**
 * What a member sees of a run's output registration: files still being saved, saved, or nothing to
 * say. A skipped or stuck row and a row already cleaned up all read {@code none}.
 */
public enum RegistrationView {
    PENDING,
    DONE,
    NONE;

    /** The registration status of the run's output row; {@code null} when the run has no row. */
    public static RegistrationView of(RegistrationStatus status) {
        if (status == RegistrationStatus.PENDING) {
            return PENDING;
        }
        if (status == RegistrationStatus.DONE) {
            return DONE;
        }
        return NONE;
    }

    /** The value stored in {@code space_asset_registration.status}; {@code null} when there is no row. */
    public static RegistrationView ofColumn(String status) {
        return status == null ? NONE : of(RegistrationStatus.fromColumn(status));
    }

    /** The wire value of the response. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
