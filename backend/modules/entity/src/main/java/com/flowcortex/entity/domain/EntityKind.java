package com.flowcortex.entity.domain;

import java.util.Locale;
import java.util.Optional;

/** What an entity is. The kind is fixed at creation. */
public enum EntityKind {
    BRAND,
    PRODUCT,
    CHARACTER,
    VOICE;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind whose lower-case wire value is exactly {@code value}. */
    public static Optional<EntityKind> parse(String value) {
        for (EntityKind kind : values()) {
            if (kind.wire().equals(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
