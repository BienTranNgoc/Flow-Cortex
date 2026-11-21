package com.flowcortex.user.preferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The four privacy keys. Stored and patch instances leave a key {@code null} when the member never
 * chose it; {@link PreferenceMerge#withDefaults} fills them for every answer.
 */
public record PrivacySettings(Visibility profile, Visibility activity, Visibility message, StatsVisibility stats) {

    public static final PrivacySettings EMPTY = new PrivacySettings(null, null, null, null);

    /** Reads a stored map, skipping keys and values this version does not know. */
    static PrivacySettings fromMap(Map<String, String> stored) {
        return new PrivacySettings(
                WireValue.parse(Visibility.class, stored.get("profile")).orElse(null),
                WireValue.parse(Visibility.class, stored.get("activity")).orElse(null),
                WireValue.parse(Visibility.class, stored.get("message")).orElse(null),
                WireValue.parse(StatsVisibility.class, stored.get("stats")).orElse(null));
    }

    Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (profile != null) {
            map.put("profile", profile.wire());
        }
        if (activity != null) {
            map.put("activity", activity.wire());
        }
        if (message != null) {
            map.put("message", message.wire());
        }
        if (stats != null) {
            map.put("stats", stats.wire());
        }
        return map;
    }
}
