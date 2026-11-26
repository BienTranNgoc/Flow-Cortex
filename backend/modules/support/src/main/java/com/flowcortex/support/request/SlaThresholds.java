package com.flowcortex.support.request;

import com.flowcortex.settings.api.Settings;
import com.flowcortex.support.config.SupportSlaSettings;
import java.time.Duration;

/** The four SLA thresholds as they read at one moment; read once per list, summary or sweep. */
record SlaThresholds(int newHours, int newWarnHours, int inProgressDays, int inProgressWarnDays) {

    static SlaThresholds read(Settings settings) {
        return new SlaThresholds(
                settings.get(SupportSlaSettings.NEW_HOURS).effective(),
                settings.get(SupportSlaSettings.NEW_WARN_HOURS).effective(),
                settings.get(SupportSlaSettings.IN_PROGRESS_DAYS).effective(),
                settings.get(SupportSlaSettings.IN_PROGRESS_WARN_DAYS).effective());
    }

    Duration newLimit() {
        return Duration.ofHours(newHours);
    }

    Duration inProgressLimit() {
        return Duration.ofDays(inProgressDays);
    }

    /** A warning at or above its limit collapses to the limit, so nothing reads due soon only. */
    Duration newWarnCut() {
        return Duration.ofHours(Math.min(newWarnHours, newHours));
    }

    Duration inProgressWarnCut() {
        return Duration.ofDays(Math.min(inProgressWarnDays, inProgressDays));
    }
}
