package com.flowcortex.entity.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gives each tag the spelling its space already uses for the same text key. */
public final class TagLabels {

    /** A tag as stored: its text key and the label shown. */
    public record Tag(String key, String label) {
    }

    private TagLabels() {
    }

    /**
     * One tag per text key, in the order first typed. A key the space already carries keeps the space's label
     * ({@code spaceLabelByKey}, keyed by {@link EntityText#key}); a new key keeps the typed label.
     */
    public static List<Tag> resolve(List<String> labels, Map<String, String> spaceLabelByKey) {
        Map<String, Tag> byKey = new LinkedHashMap<>();
        for (String label : labels == null ? List.<String>of() : labels) {
            String key = EntityText.key(label);
            byKey.computeIfAbsent(key, k -> new Tag(k, spaceLabelByKey.getOrDefault(k, label)));
        }
        return List.copyOf(byKey.values());
    }
}
