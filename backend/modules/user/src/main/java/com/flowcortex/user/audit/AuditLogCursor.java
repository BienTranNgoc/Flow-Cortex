package com.flowcortex.user.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Position of the last row of a page: its time to the microsecond and its id. Opaque to clients, who
 * only hand back what a page gave them.
 */
record AuditLogCursor(Instant at, UUID id) {

    private static final int MAX_ENCODED_LENGTH = 120;
    private static final Pattern PAYLOAD = Pattern.compile("(\\d{1,18}):([0-9a-f-]{36})");
    private static final long MICROS = 1_000_000L;

    String encode() {
        long micros = at.getEpochSecond() * MICROS + at.getNano() / 1_000;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** @throws AuditLogFilter.Invalid when the text is not a cursor this class produced */
    static AuditLogCursor decode(String encoded) {
        try {
            if (encoded.length() > MAX_ENCODED_LENGTH) {
                throw new IllegalArgumentException("too long");
            }
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            Matcher matcher = PAYLOAD.matcher(payload);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("malformed");
            }
            long micros = Long.parseLong(matcher.group(1));
            Instant at = Instant.ofEpochSecond(micros / MICROS, micros % MICROS * 1_000);
            return new AuditLogCursor(at, UUID.fromString(matcher.group(2)));
        } catch (IllegalArgumentException ex) {
            throw new AuditLogFilter.Invalid(Map.of("cursor", "audit.filter.cursor"));
        }
    }
}
