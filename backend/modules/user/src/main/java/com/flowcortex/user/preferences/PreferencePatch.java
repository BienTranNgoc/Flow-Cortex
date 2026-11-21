package com.flowcortex.user.preferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Parses a PATCH body checked key by key on the raw JSON, because a record field cannot tell an
 * absent key from an explicit {@code null}. A non-object body, an unknown key, a {@code null} and a
 * value outside the closed set are each a refusal (empty); a key left out stays {@code null}.
 */
public final class PreferencePatch {

    private PreferencePatch() {
    }

    public static Optional<UiPreferences> parseUi(JsonNode body) {
        return strings(body, Set.of("theme", "sidebarMode"))
                .filter(values -> valid(values, "theme", Theme.class))
                .filter(values -> valid(values, "sidebarMode", SidebarMode.class))
                .map(UiPreferences::fromMap);
    }

    public static Optional<PrivacySettings> parsePrivacy(JsonNode body) {
        return strings(body, Set.of("profile", "activity", "message", "stats"))
                .filter(values -> valid(values, "profile", Visibility.class))
                .filter(values -> valid(values, "activity", Visibility.class))
                .filter(values -> valid(values, "message", Visibility.class))
                .filter(values -> valid(values, "stats", StatsVisibility.class))
                .map(PrivacySettings::fromMap);
    }

    /** The body's entries as strings, or empty for a non-object, an unknown key or a non-string value. */
    private static Optional<Map<String, String>> strings(JsonNode body, Set<String> allowedKeys) {
        if (body == null || !body.isObject()) {
            return Optional.empty();
        }
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : body.properties()) {
            if (!allowedKeys.contains(entry.getKey()) || !entry.getValue().isString()) {
                return Optional.empty();
            }
            values.put(entry.getKey(), entry.getValue().stringValue());
        }
        return Optional.of(values);
    }

    private static <E extends Enum<E> & WireValue> boolean valid(Map<String, String> values, String key,
            Class<E> type) {
        return !values.containsKey(key) || WireValue.parse(type, values.get(key)).isPresent();
    }
}
