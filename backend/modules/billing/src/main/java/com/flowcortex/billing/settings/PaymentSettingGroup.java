package com.flowcortex.billing.settings;

import java.util.Locale;

/** The console section a payment setting sits in; declaration order is the order the console lists them. */
public enum PaymentSettingGroup {
    REGION;

    /** The lower-case name the API and the console use. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
