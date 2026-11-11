package com.flowcortex.profile.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** One stored picture of a member: an original kept in a slot's history, or the slot's current image. */
public record ProfileImage(UUID id, UUID userId, Slot slot, Role role, String objectKey, String mimeType, int width,
        int height, long bytes, Instant createdAt) {

    /** Which picture of the profile an image is for. */
    public enum Slot {
        AVATAR, COVER;

        /** The lowercase name used in routes and in the database. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Slot> fromWire(String value) {
            for (Slot slot : values()) {
                if (slot.wire().equals(value)) {
                    return Optional.of(slot);
                }
            }
            return Optional.empty();
        }
    }

    /** An original is an upload kept for reuse; the current image is the one the slot shows. */
    public enum Role {
        ORIGINAL, CURRENT;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
