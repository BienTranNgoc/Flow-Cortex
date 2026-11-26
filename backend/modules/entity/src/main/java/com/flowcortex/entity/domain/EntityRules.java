package com.flowcortex.entity.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a request may carry into an entity. Each check answers the cleaned value or the first violation, and a
 * violation names the field and a message key, never the refused value.
 */
public final class EntityRules {

    public static final int NAME_MAX = 200;
    public static final int FIELD_TEXT_MAX = 4_000;
    public static final int FIELD_LIST_MAX = 20;
    public static final int FIELDS_SERIALIZED_MAX = 32_768;
    public static final int PARTS_MAX = 20;
    public static final int TAGS_MAX = 20;
    public static final int TAG_MAX = 40;
    public static final int CATEGORY_MAX = 40;
    public static final int LABEL_MAX = 60;

    private static final String NAME = "name";
    private static final String FIELDS = "fields";
    private static final String PARTS = "parts";
    private static final String TAGS = "tags";
    private static final String CATEGORY = "categoryLabel";

    private static final Pattern HEX_COLOUR = Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    private static final Pattern SIGNED_URL = Pattern.compile("x-amz-", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_DATA_URI = Pattern.compile("^[\\s\\p{Z}]*data:", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMBEDDED_BASE64_DATA_URI = Pattern.compile(
            "data:[\\w.+-]+/[\\w.+-]+(?:;[\\w.+-]+=[\\w.+-]+)*;base64,", Pattern.CASE_INSENSITIVE);

    /** The cleaned value, or the violation that refused it ({@code value} is then {@code null}). */
    public record Checked<T>(T value, RuleViolation violation) {

        static <T> Checked<T> ok(T value) {
            return new Checked<>(value, null);
        }

        static <T> Checked<T> refused(String field, String reason) {
            return new Checked<>(null, violationOf(field, reason));
        }

        public boolean valid() {
            return violation == null;
        }
    }

    private EntityRules() {
    }

    /** NFC and trimmed, 1-200 code points. */
    public static Checked<String> checkName(String name) {
        String normalized = name == null ? "" : Normalizer.normalize(name, Normalizer.Form.NFC).strip();
        if (normalized.isEmpty() || codePoints(normalized) > NAME_MAX || refusedContent(normalized)) {
            return Checked.refused(NAME, "invalid");
        }
        return Checked.ok(normalized);
    }

    /** Only the kind's keys; strings and lists within their caps; a serialized size within its cap. */
    public static Checked<Map<String, Object>> checkFields(EntityKind kind, Map<String, Object> fields) {
        Map<String, Object> input = fields == null ? Map.of() : fields;
        List<String> allowed = KindRules.fieldKeys(kind);
        for (String key : input.keySet()) {
            if (key == null || !allowed.contains(key)) {
                return Checked.refused(FIELDS, "unknownKey");
            }
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Optional<Object> value = checkFieldValue(entry.getKey(), entry.getValue());
            if (value.isEmpty()) {
                return Checked.refused(FIELDS, "invalid");
            }
            cleaned.put(entry.getKey(), value.get());
        }
        if (serializedLength(cleaned) > FIELDS_SERIALIZED_MAX) {
            return Checked.refused(FIELDS, "invalid");
        }
        return Checked.ok(cleaned);
    }

    /** At most 20 parts, each with an asset id, a role of the kind and an optional label; the order is kept. */
    public static Checked<List<PartInput>> checkPartShapes(EntityKind kind, List<PartInput> parts) {
        List<PartInput> input = parts == null ? List.of() : parts;
        if (input.size() > PARTS_MAX) {
            return Checked.refused(PARTS, "tooMany");
        }
        List<String> roles = KindRules.partRoles(kind);
        for (PartInput part : input) {
            if (part == null || part.assetId() == null) {
                return Checked.refused(PARTS, "invalid");
            }
            if (part.role() == null || !roles.contains(part.role())) {
                return Checked.refused(PARTS, "roleNotAllowed");
            }
        }
        List<PartInput> cleaned = new ArrayList<>(input.size());
        for (PartInput part : input) {
            Checked<String> label = checkLabel(part.label());
            if (!label.valid()) {
                return Checked.refused(PARTS, "labelInvalid");
            }
            cleaned.add(new PartInput(part.assetId(), part.role(), label.value()));
        }
        return Checked.ok(List.copyOf(cleaned));
    }

    /** Every part's asset must be listed in {@code assetKinds} (asset id to asset kind), then be of the kind its role needs. */
    public static Optional<RuleViolation> checkPartAssets(List<PartInput> parts, Map<UUID, String> assetKinds) {
        for (PartInput part : parts) {
            if (!assetKinds.containsKey(part.assetId())) {
                return Optional.of(violationOf(PARTS, "assetNotFound"));
            }
        }
        for (PartInput part : parts) {
            Optional<String> needed = KindRules.assetKindOf(part.role());
            if (needed.isPresent() && !needed.get().equals(assetKinds.get(part.assetId()))) {
                return Optional.of(violationOf(PARTS, "kindMismatch"));
            }
        }
        return Optional.empty();
    }

    /** Labels of 1-40 code points, one per text key (the first spelling wins), at most 20 keys. */
    public static Checked<List<String>> checkTags(List<String> tags) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String tag : tags == null ? List.<String>of() : tags) {
            Checked<String> label = checkText(tag, TAG_MAX, TAGS, "invalid");
            if (!label.valid()) {
                return Checked.refused(TAGS, "invalid");
            }
            byKey.putIfAbsent(EntityText.key(label.value()), label.value());
        }
        if (byKey.size() > TAGS_MAX) {
            return Checked.refused(TAGS, "tooMany");
        }
        return Checked.ok(List.copyOf(byKey.values()));
    }

    /** {@code null} stays {@code null} (no category); otherwise 1-40 code points. */
    public static Checked<String> checkCategory(String category) {
        return category == null ? Checked.ok(null) : checkText(category, CATEGORY_MAX, CATEGORY, "invalid");
    }

    /** A part label: {@code null} stays {@code null}; otherwise 1-60 code points. */
    public static Checked<String> checkLabel(String label) {
        return label == null ? Checked.ok(null) : checkText(label, LABEL_MAX, PARTS, "labelInvalid");
    }

    /** True when the value, or any string nested in a map or collection, carries a signed-URL marker or a data URI. */
    public static boolean containsSignedUrlOrDataUri(Object value) {
        if (value instanceof CharSequence text) {
            return SIGNED_URL.matcher(text).find() || LEADING_DATA_URI.matcher(text).find()
                    || EMBEDDED_BASE64_DATA_URI.matcher(text).find();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().anyMatch(entry ->
                    containsSignedUrlOrDataUri(entry.getKey()) || containsSignedUrlOrDataUri(entry.getValue()));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(EntityRules::containsSignedUrlOrDataUri);
        }
        return false;
    }

    private static Checked<String> checkText(String raw, int max, String field, String reason) {
        String label = EntityText.label(raw);
        if (label.isEmpty() || codePoints(label) > max || EntityText.key(label).isEmpty() || refusedContent(label)) {
            return Checked.refused(field, reason);
        }
        return Checked.ok(label);
    }

    private static Optional<Object> checkFieldValue(String key, Object value) {
        if (KindRules.isListField(key)) {
            if (!(value instanceof List<?> list) || list.size() > FIELD_LIST_MAX) {
                return Optional.empty();
            }
            boolean colours = "colors".equals(key);
            List<String> entries = new ArrayList<>(list.size());
            for (Object entry : list) {
                if (!(entry instanceof String text) || !textFits(text) || (colours && !HEX_COLOUR.matcher(text).matches())) {
                    return Optional.empty();
                }
                entries.add(text);
            }
            return refusedContent(entries) ? Optional.empty() : Optional.of(List.copyOf(entries));
        }
        if (!(value instanceof String text) || !textFits(text) || refusedContent(text)) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /** A signed URL or data URI, or a NUL character, which PostgreSQL text and jsonb refuse. */
    private static boolean refusedContent(Object value) {
        return containsSignedUrlOrDataUri(value) || containsNul(value);
    }

    private static boolean containsNul(Object value) {
        if (value instanceof CharSequence text) {
            return text.chars().anyMatch(c -> c == 0);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(EntityRules::containsNul);
        }
        return false;
    }

    private static boolean textFits(String text) {
        return text.length() <= FIELD_TEXT_MAX * 2 && codePoints(text) <= FIELD_TEXT_MAX;
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /** Characters of the compact JSON form of a map of strings and string lists, as the jsonb column is written. */
    private static long serializedLength(Map<String, Object> fields) {
        long length = 2;
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            length += (first ? 0 : 1) + jsonStringLength(entry.getKey()) + 1;
            first = false;
            if (entry.getValue() instanceof List<?> list) {
                length += 2L + Math.max(0, list.size() - 1);
                for (Object item : list) {
                    length += jsonStringLength((String) item);
                }
            } else {
                length += jsonStringLength((String) entry.getValue());
            }
        }
        return length;
    }

    private static long jsonStringLength(String text) {
        long length = 2;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\' || c == '\b' || c == '\f' || c == '\n' || c == '\r' || c == '\t') {
                length += 2;
            } else if (c < 0x20) {
                length += 6;
            } else {
                length++;
            }
        }
        return length;
    }

    private static RuleViolation violationOf(String field, String reason) {
        return new RuleViolation(field, "entity." + field + "." + reason);
    }
}
