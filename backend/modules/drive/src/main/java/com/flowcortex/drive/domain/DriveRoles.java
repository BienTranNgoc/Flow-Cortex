package com.flowcortex.drive.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The one table of semantic roles and the content type each belongs to. */
public final class DriveRoles {

    /** What an item or Memory entry holds; the lowercase name is its wire and database value. */
    public enum ContentType {
        TEXT, IMAGE, VIDEO, AUDIO;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<ContentType> fromWire(String value) {
            for (ContentType type : values()) {
                if (type.wire().equals(value)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }

    private static final Map<String, ContentType> TABLE = table();

    private DriveRoles() {
    }

    private static Map<String, ContentType> table() {
        Map<String, ContentType> roles = new LinkedHashMap<>();
        roles.put("brand_voice_text", ContentType.TEXT);
        roles.put("company_bio", ContentType.TEXT);
        roles.put("audience_note", ContentType.TEXT);
        roles.put("logo", ContentType.IMAGE);
        roles.put("product_photo", ContentType.IMAGE);
        roles.put("reference_face", ContentType.IMAGE);
        roles.put("persona_image", ContentType.IMAGE);
        roles.put("image_asset", ContentType.IMAGE);
        roles.put("driver_video", ContentType.VIDEO);
        roles.put("video_asset", ContentType.VIDEO);
        roles.put("audio_asset", ContentType.AUDIO);
        return Collections.unmodifiableMap(roles);
    }

    public static Optional<ContentType> contentTypeOf(String role) {
        return Optional.ofNullable(role == null ? null : TABLE.get(role));
    }

    /** The roles of one content type, in table order. */
    public static List<String> rolesOf(ContentType type) {
        List<String> roles = new ArrayList<>();
        TABLE.forEach((role, owner) -> {
            if (owner == type) {
                roles.add(role);
            }
        });
        return List.copyOf(roles);
    }

    /** Every role, in table order. */
    public static List<String> all() {
        return List.copyOf(TABLE.keySet());
    }
}
