package com.flowcortex.trash.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmptyCursorTest {

    private static final Instant AT = Instant.parse("2026-11-01T08:30:15.123456Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Test
    void roundTripsRankDeletedAtAndId() {
        EmptyCursor cursor = new EmptyCursor(10, AT, ID);

        String encoded = cursor.encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(EmptyCursor.decode(encoded)).contains(cursor);
    }

    @Test
    void malformedCursorIsRejected() {
        String valid = new EmptyCursor(10, AT, ID).encode();

        assertThat(EmptyCursor.decode("!!")).isEmpty();
        assertThat(EmptyCursor.decode(valid.substring(0, valid.length() - 6))).isEmpty();
        assertThat(EmptyCursor.decode(base64("10|" + AT))).isEmpty();
        assertThat(EmptyCursor.decode(base64("10|" + AT + "|" + ID + "|x"))).isEmpty();
        assertThat(EmptyCursor.decode(base64("ten|" + AT + "|" + ID))).isEmpty();
        assertThat(EmptyCursor.decode(base64("10|yesterday|" + ID))).isEmpty();
        assertThat(EmptyCursor.decode(base64("10|" + AT + "|not-a-uuid"))).isEmpty();
        assertThat(EmptyCursor.decode(base64("10|" + AT + "|1-1-1-1-1"))).isEmpty();
        assertThat(EmptyCursor.decode("")).isEmpty();
        assertThat(EmptyCursor.decode(null)).isEmpty();
    }

    @Test
    void ordersByRankThenDeletedAtThenId() {
        EmptyCursor rank10Old = new EmptyCursor(10, AT, ID);
        EmptyCursor rank10OldNextId = new EmptyCursor(10, AT, UUID.fromString("00000000-0000-0000-0000-000000000006"));
        EmptyCursor rank10New = new EmptyCursor(10, AT.plusSeconds(1), ID);
        EmptyCursor rank20Oldest = new EmptyCursor(20, AT.minusSeconds(3600), ID);

        assertThat(List.of(rank20Oldest, rank10New, rank10OldNextId, rank10Old).stream().sorted().toList())
                .containsExactly(rank10Old, rank10OldNextId, rank10New, rank20Oldest);
    }

    private static String base64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
