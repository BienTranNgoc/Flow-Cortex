package com.flowcortex.user.preferences;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/** The closed UI preference set; a {@code null} key is one the member never chose (or, in a patch, left out). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UiPreferences(Theme theme, SidebarMode sidebarMode) {

    public static final UiPreferences EMPTY = new UiPreferences(null, null);

    /** Reads a stored map, skipping keys and values this version does not know. */
    static UiPreferences fromMap(Map<String, String> stored) {
        return new UiPreferences(
                WireValue.parse(Theme.class, stored.get("theme")).orElse(null),
                WireValue.parse(SidebarMode.class, stored.get("sidebarMode")).orElse(null));
    }

    Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (theme != null) {
            map.put("theme", theme.wire());
        }
        if (sidebarMode != null) {
            map.put("sidebarMode", sidebarMode.wire());
        }
        return map;
    }
}
