package com.flowcortex.chat.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.chat.conversation.ListCursor.Sort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListCursorTest {

    private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    @Test
    void aCursorRoundTripsWithMicrosecondPrecision() {
        Instant at = Instant.parse("2026-11-08T10:15:30.123456Z");

        for (Sort sort : Sort.values()) {
            String encoded = new ListCursor(sort, at, ID).encode();

            assertThat(ListCursor.decode(encoded, sort)).contains(new ListCursor(sort, at, ID));
        }
    }

    @Test
    void aTimestampFinerThanAMicrosecondIsCutToTheMicrosecondPostgresStores() {
        Instant at = Instant.parse("2026-11-08T10:15:30.123456789Z");

        Optional<ListCursor> decoded = ListCursor.decode(new ListCursor(Sort.NEWEST, at, ID).encode(), Sort.NEWEST);

        assertThat(decoded).map(ListCursor::lastActivityAt).contains(Instant.parse("2026-11-08T10:15:30.123456Z"));
    }

    @Test
    void textThatIsNotACursorIsRefused() {
        assertThat(ListCursor.decode("abc", Sort.NEWEST)).isEmpty();
        assertThat(ListCursor.decode("", Sort.NEWEST)).isEmpty();
        assertThat(ListCursor.decode("***", Sort.NEWEST)).isEmpty();
    }

    @Test
    void aCursorMadeForTheOtherSortIsRefused() {
        String newest = new ListCursor(Sort.NEWEST, Instant.parse("2026-11-08T10:15:30Z"), ID).encode();

        assertThat(ListCursor.decode(newest, Sort.OLDEST)).isEmpty();
    }

    @Test
    void aCursorMissingAPartOrCarryingABadPartIsRefused() {
        for (String raw : new String[] {"N|1000", "N|1000|" + ID + "|x", "N|abc|" + ID, "N|1000|not-a-uuid",
                "X|1000|" + ID, "|1000|" + ID}) {
            assertThat(ListCursor.decode(base64(raw), Sort.NEWEST)).as(raw).isEmpty();
        }
    }

    @Test
    void aCursorThatIsNotInItsCanonicalFormIsRefused() {
        String padded = Base64.getUrlEncoder().encodeToString(("N|1000|" + ID).getBytes(StandardCharsets.UTF_8));

        assertThat(padded).endsWith("=");
        assertThat(ListCursor.decode(padded, Sort.NEWEST)).isEmpty();
        assertThat(ListCursor.decode(base64("N|01000|" + ID), Sort.NEWEST)).isEmpty();
    }

    private static String base64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
