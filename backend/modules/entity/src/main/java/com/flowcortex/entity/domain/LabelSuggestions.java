package com.flowcortex.entity.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups the part labels used in a space into the suggestions the labels route answers. */
public final class LabelSuggestions {

    /** One use of a label on a live part: the label as spelled, its text key and when the part was made. */
    public record LabelUse(String label, String key, Instant usedAt) {
    }

    /** A label to suggest and how many uses share its key. */
    public record Suggestion(String label, int count) {
    }

    private LabelSuggestions() {
    }

    /**
     * Groups {@code uses} by key; each group shows its most recently spelled label. Most used first, then most recently
     * used; {@code q} (any text, compared by key) must be a word prefix of the key, and a blank {@code q} keeps all.
     */
    public static List<Suggestion> group(List<LabelUse> uses, String q, int limit) {
        String needle = EntityText.key(q);
        Map<String, Group> byKey = new LinkedHashMap<>();
        for (LabelUse use : uses) {
            if (matches(use.key(), needle)) {
                byKey.computeIfAbsent(use.key(), key -> new Group()).add(use);
            }
        }
        List<Group> groups = new ArrayList<>(byKey.values());
        groups.sort(Comparator.comparingInt((Group g) -> g.count).reversed()
                .thenComparing(Comparator.comparing((Group g) -> g.latest.usedAt()).reversed())
                .thenComparing(g -> g.latest.key()));
        return groups.stream().limit(Math.max(0, limit)).map(g -> new Suggestion(g.latest.label(), g.count)).toList();
    }

    private static boolean matches(String key, String needle) {
        return needle.isEmpty() || key.startsWith(needle) || key.contains(" " + needle);
    }

    private static final class Group {

        private int count;
        private LabelUse latest;

        void add(LabelUse use) {
            count++;
            if (latest == null || !use.usedAt().isBefore(latest.usedAt())) {
                latest = use;
            }
        }
    }
}
