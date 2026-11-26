package com.flowcortex.chat.conversation;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the next list page starts: the last row's activity time and id, plus the sort it was made for. Opaque to
 * clients: base64url of {@code <sort letter>|<epoch microseconds>|<id>}. The time is kept in microseconds because
 * Postgres stores no finer, so a millisecond cursor would skip rows sharing a millisecond.
 */
public record ListCursor(Sort sort, Instant lastActivityAt, UUID id) {

    /** Direction of the list; the letter is what the cursor records. */
    public enum Sort {
        NEWEST('N'), OLDEST('O');

        private final char letter;

        Sort(char letter) {
            this.letter = letter;
        }

        char letter() {
            return letter;
        }
    }

    public ListCursor {
        lastActivityAt = lastActivityAt.truncatedTo(ChronoUnit.MICROS);
    }

    public String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, lastActivityAt);
        String raw = sort.letter() + "|" + micros + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The cursor in {@code raw}, or empty when it is malformed, not in the form {@link #encode()} writes, or
     * was made for the other sort.
     */
    public static Optional<ListCursor> decode(String raw, Sort expected) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || parts[0].length() != 1 || parts[0].charAt(0) != expected.letter()) {
                return Optional.empty();
            }
            Instant at = Instant.EPOCH.plus(Long.parseLong(parts[1]), ChronoUnit.MICROS);
            ListCursor cursor = new ListCursor(expected, at, UUID.fromString(parts[2]));
            return cursor.encode().equals(raw) ? Optional.of(cursor) : Optional.empty();
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException e) {
            return Optional.empty();
        }
    }
}
