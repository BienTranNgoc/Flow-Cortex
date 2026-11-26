package com.flowcortex.user.api;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Position of the last item of a timeline page: the items that follow it are those later in the order
 * time descending, then source ascending, then id descending. Instants keep microseconds, the precision
 * Postgres stores.
 */
public record ActivityCursor(Instant at, String source, UUID id) {

    private static final Pattern SOURCE = Pattern.compile("[a-z_]{1,40}");
    private static final long MIN_EPOCH_SECOND = Instant.parse("0001-01-01T00:00:00Z").getEpochSecond();
    private static final long MAX_EPOCH_SECOND = Instant.parse("9999-12-31T23:59:59Z").getEpochSecond();
    private static final long MICROS = 1_000_000L;

    /** Opaque, URL-safe form handed to the client. */
    public String encode() {
        long micros = at.getEpochSecond() * MICROS + at.getNano() / 1_000L;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + ":" + source + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException if {@code value} is not a cursor this class encoded */
    public static ActivityCursor decode(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("cursor is empty");
        }
        String text = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        int first = text.indexOf(':');
        int last = text.lastIndexOf(':');
        if (first < 1 || last <= first) {
            throw new IllegalArgumentException("cursor has the wrong shape");
        }
        long micros = Long.parseLong(text.substring(0, first));
        long seconds = Math.floorDiv(micros, MICROS);
        if (seconds < MIN_EPOCH_SECOND || seconds > MAX_EPOCH_SECOND) {
            throw new IllegalArgumentException("cursor instant is out of range");
        }
        String source = text.substring(first + 1, last);
        if (!SOURCE.matcher(source).matches()) {
            throw new IllegalArgumentException("cursor source is invalid");
        }
        String idText = text.substring(last + 1);
        UUID id = UUID.fromString(idText);
        if (!id.toString().equals(idText)) {
            throw new IllegalArgumentException("cursor id is not canonical");
        }
        return new ActivityCursor(Instant.ofEpochSecond(seconds, Math.floorMod(micros, MICROS) * 1_000L), source, id);
    }

    /** The bound a source reads after {@code cursor}; {@code cursor} is {@code null} on the first page. */
    public static Bound boundFor(ActivityCursor cursor, String source) {
        return cursor == null ? Bound.NONE : cursor.boundFor(source);
    }

    /**
     * A source that sorts before the cursor's source has already returned every item of the cursor's instant, one
     * that sorts after has not, and the cursor's own source continues below the cursor's id.
     */
    public Bound boundFor(String candidate) {
        int order = candidate.compareTo(source);
        if (order < 0) {
            return new Bound(Bound.Mode.BEFORE, at, null);
        }
        return order > 0 ? new Bound(Bound.Mode.THROUGH, at, null) : new Bound(Bound.Mode.TIE_BREAK, at, id);
    }

    /**
     * What one source adds to its query so it returns only items after the cursor, in SQL over named parameters
     * {@code afterAt} and {@code afterId}. The row comparison keeps an {@code (owner, time DESC, id DESC)} index
     * supplying the order.
     */
    public record Bound(Mode mode, Instant at, UUID id) {

        public static final Bound NONE = new Bound(Mode.NONE, null, null);

        public enum Mode { NONE, BEFORE, THROUGH, TIE_BREAK }

        /** An {@code AND ...} fragment over the two columns, empty when nothing is bounded. */
        public String clause(String atColumn, String idColumn) {
            return switch (mode) {
                case NONE -> "";
                case BEFORE -> " AND " + atColumn + " < :afterAt";
                case THROUGH -> " AND " + atColumn + " <= :afterAt";
                case TIE_BREAK -> " AND (" + atColumn + ", " + idColumn + ") < (:afterAt, :afterId)";
            };
        }

        /** The named parameters {@link #clause} refers to. */
        public Map<String, Object> params() {
            return switch (mode) {
                case NONE -> Map.of();
                case BEFORE, THROUGH -> Map.of("afterAt", Timestamp.from(at));
                case TIE_BREAK -> Map.of("afterAt", Timestamp.from(at), "afterId", id);
            };
        }
    }
}
