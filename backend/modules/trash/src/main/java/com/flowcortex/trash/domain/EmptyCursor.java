package com.flowcortex.trash.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/** A position in an empty run: entries sort by purge rank, delete time, then id. */
public record EmptyCursor(int rank, Instant deletedAt, UUID id) implements Comparable<EmptyCursor> {

    private static final String SEPARATOR = "|";
    private static final Comparator<EmptyCursor> ORDER = Comparator.comparingInt(EmptyCursor::rank)
            .thenComparing(EmptyCursor::deletedAt)
            .thenComparing(EmptyCursor::id);

    /** The opaque form handed to the client: base64url without padding. */
    public String encode() {
        String text = rank + SEPARATOR + deletedAt + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Empty for anything {@link #encode()} could not have produced. */
    public static Optional<EmptyCursor> decode(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        try {
            String text = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = text.split("\\" + SEPARATOR, -1);
            if (parts.length != 3) {
                return Optional.empty();
            }
            EmptyCursor cursor = new EmptyCursor(Integer.parseInt(parts[0]), Instant.parse(parts[1]), UUID.fromString(parts[2]));
            return cursor.encode().equals(value) ? Optional.of(cursor) : Optional.empty();
        } catch (IllegalArgumentException | DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(EmptyCursor other) {
        return ORDER.compare(this, other);
    }
}
