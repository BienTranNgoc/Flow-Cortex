package com.flowcortex.entity.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** The change codes between two snapshots, in the fixed order a version records them. */
public final class EntityChanges {

    public static final String CREATED = "created";
    public static final String RENAMED = "renamed";
    public static final String HANDLE_CHANGED = "handleChanged";
    public static final String FIELDS_CHANGED = "fieldsChanged";
    public static final String PARTS_CHANGED = "partsChanged";
    public static final String TAGS_CHANGED = "tagsChanged";
    public static final String CATEGORY_CHANGED = "categoryChanged";
    public static final String RESTORED = "restored";

    /**
     * One recorded change. {@code added} and {@code removed} are the tag labels of {@code tagsChanged} (empty for any
     * other code); {@code from} is the source version of {@code restored} (otherwise {@code null}).
     */
    public record Change(String code, List<String> added, List<String> removed, Integer from) {

        public Change {
            added = added == null ? List.of() : List.copyOf(added);
            removed = removed == null ? List.of() : List.copyOf(removed);
        }

        public static Change of(String code) {
            return new Change(code, List.of(), List.of(), null);
        }

        public static Change created() {
            return of(CREATED);
        }

        public static Change restored(int fromVersion) {
            return new Change(RESTORED, List.of(), List.of(), fromVersion);
        }

        static Change tagsChanged(List<String> added, List<String> removed) {
            return new Change(TAGS_CHANGED, added, removed, null);
        }
    }

    private EntityChanges() {
    }

    /**
     * What differs from {@code before} to {@code after}; empty when nothing does. Fields compare as canonical JSON
     * (sorted keys), parts by their order, tags as a set by text key, category by its label.
     */
    public static List<Change> diff(EntitySnapshot before, EntitySnapshot after) {
        List<Change> changes = new ArrayList<>();
        if (!Objects.equals(before.name(), after.name())) {
            changes.add(Change.of(RENAMED));
        }
        if (!Objects.equals(before.handle(), after.handle())) {
            changes.add(Change.of(HANDLE_CHANGED));
        }
        if (!canonical(before.fields()).equals(canonical(after.fields()))) {
            changes.add(Change.of(FIELDS_CHANGED));
        }
        if (!orderedParts(before).equals(orderedParts(after))) {
            changes.add(Change.of(PARTS_CHANGED));
        }
        List<String> added = labelsMissingFrom(after.tags(), before.tags());
        List<String> removed = labelsMissingFrom(before.tags(), after.tags());
        if (!added.isEmpty() || !removed.isEmpty()) {
            changes.add(Change.tagsChanged(added, removed));
        }
        if (!Objects.equals(before.categoryLabel(), after.categoryLabel())) {
            changes.add(Change.of(CATEGORY_CHANGED));
        }
        return List.copyOf(changes);
    }

    private static List<String> labelsMissingFrom(List<String> labels, List<String> others) {
        Set<String> otherKeys = new LinkedHashSet<>();
        others.forEach(label -> otherKeys.add(EntityText.key(label)));
        Map<String, String> missing = new LinkedHashMap<>();
        for (String label : labels) {
            String key = EntityText.key(label);
            if (!otherKeys.contains(key)) {
                missing.putIfAbsent(key, label);
            }
        }
        return List.copyOf(missing.values());
    }

    private static List<List<Object>> orderedParts(EntitySnapshot snapshot) {
        return snapshot.parts().stream()
                .sorted(Comparator.comparingInt(EntitySnapshot.Part::sort))
                .map(part -> Arrays.<Object>asList(part.assetId(), part.role(), part.label()))
                .toList();
    }

    /** Compact JSON text with every object's keys sorted, so equal content gives equal text whatever the insertion order. */
    private static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, entry) -> sorted.put(String.valueOf(key), entry));
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Collection<?> collection) {
            out.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else {
            writeString(value.toString(), out);
        }
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
