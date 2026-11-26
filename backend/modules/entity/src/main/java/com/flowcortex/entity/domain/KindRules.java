package com.flowcortex.entity.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The field keys and part roles of each kind, and the library asset kind each part role needs. */
public final class KindRules {

    private static final Set<String> LIST_FIELDS = Set.of("colors", "do", "dont");

    private static final Map<EntityKind, List<String>> FIELD_KEYS = new EnumMap<>(Map.of(
            EntityKind.BRAND, List.of("voice", "companyBio", "audience", "colors", "do", "dont"),
            EntityKind.PRODUCT, List.of("fullName", "description", "price", "highlights", "salesUrl"),
            EntityKind.CHARACTER, List.of("description", "voiceLabel"),
            EntityKind.VOICE, List.of("label")));

    private static final Map<EntityKind, List<String>> PART_ROLES = new EnumMap<>(Map.of(
            EntityKind.BRAND, List.of("logo"),
            EntityKind.PRODUCT, List.of("product_photo", "detail", "back", "worn"),
            EntityKind.CHARACTER, List.of("portrait", "style"),
            EntityKind.VOICE, List.of("voice_sample")));

    private static final String VOICE_SAMPLE = "voice_sample";

    private KindRules() {
    }

    public static List<String> fieldKeys(EntityKind kind) {
        return FIELD_KEYS.get(kind);
    }

    /** {@code colors}, {@code do} and {@code dont} hold a list of strings; every other field key holds a string. */
    public static boolean isListField(String key) {
        return LIST_FIELDS.contains(key);
    }

    public static List<String> partRoles(EntityKind kind) {
        return PART_ROLES.get(kind);
    }

    /** {@code audio} for {@code voice_sample}, {@code image} for every other role, empty for a role no kind has. */
    public static Optional<String> assetKindOf(String role) {
        if (VOICE_SAMPLE.equals(role)) {
            return Optional.of("audio");
        }
        boolean known = role != null && PART_ROLES.values().stream().anyMatch(roles -> roles.contains(role));
        return known ? Optional.of("image") : Optional.empty();
    }
}
