package com.flowcortex.billing.region;

import com.flowcortex.settings.api.Settings;

/**
 * The gate mode in force right now: the stored value of the mode setting, else the configured one. The
 * registry degrades a failed read to the configured value, so this never throws for a settings outage.
 */
class PaymentsRegionModeSource {

    private final Settings settings;

    PaymentsRegionModeSource(Settings settings) {
        this.settings = settings;
    }

    PaymentsRegionGateMode current() {
        return PaymentsRegionGateMode.parse(settings.get(PaymentsRegionSettings.MODE).effective());
    }
}
