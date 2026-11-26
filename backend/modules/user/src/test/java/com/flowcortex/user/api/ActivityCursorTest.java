package com.flowcortex.user.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.user.api.ActivityCursor.Bound;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivityCursorTest {

    private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final Instant AT = Instant.parse("2026-11-24T10:00:00.000001Z");

    private static String raw(String content) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void roundTripKeepsMicroseconds() {
        ActivityCursor cursor = new ActivityCursor(AT, "workflow_run", ID);

        ActivityCursor decoded = ActivityCursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
        assertThat(decoded.at().getNano()).isEqualTo(1_000);
    }

    @Test
    void theEncodedFormIsUnpaddedBase64Url() {
        String encoded = new ActivityCursor(AT, "auth", ID).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo("1795514400000001:auth:" + ID);
    }

    @Test
    void instantsBeforeTheEpochRoundTrip() {
        ActivityCursor cursor = new ActivityCursor(Instant.parse("1969-12-31T23:59:59.999999Z"), "auth", ID);

        assertThat(ActivityCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void anUndecodableValueIsInvalid() {
        assertThatThrownBy(() -> ActivityCursor.decode("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityCursor.decode("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityCursor.decode(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityCursor.decode("not base64 !!")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWellFormedEnvelopeWithABadPartIsInvalid() {
        assertThatThrownBy(() -> ActivityCursor.decode(raw("nocolons"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityCursor.decode(raw("x:auth:" + ID))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityCursor.decode(raw("1795514400000001:auth:not-a-uuid")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityCursor.decode(raw("1795514400000001::" + ID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityCursor.decode(raw("1795514400000001:Auth Event:" + ID)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aUuidThatIsNotInCanonicalLowerCaseIsInvalid() {
        assertThatThrownBy(() -> ActivityCursor.decode(raw("1795514400000001:auth:" + ID.toString().toUpperCase())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityCursor.decode(raw("1795514400000001:auth:1-1-1-1-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInstantOutsideTheYearsThePlatformStoresIsInvalid() {
        assertThatThrownBy(() -> ActivityCursor.decode(raw("9223372036854775807:auth:" + ID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityCursor.decode(raw("-9223372036854775808:auth:" + ID)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundForASourceBeforeTheCursorsSourceExcludesTheCursorInstant() {
        Bound bound = new ActivityCursor(AT, "workflow", ID).boundFor("auth");

        assertThat(bound.mode()).isEqualTo(Bound.Mode.BEFORE);
        assertThat(bound.clause("at", "id")).isEqualTo(" AND at < :afterAt");
        assertThat(bound.params()).containsOnlyKeys("afterAt");
    }

    @Test
    void boundForASourceAfterTheCursorsSourceIncludesTheCursorInstant() {
        Bound bound = new ActivityCursor(AT, "workflow", ID).boundFor("workflow_run");

        assertThat(bound.mode()).isEqualTo(Bound.Mode.THROUGH);
        assertThat(bound.clause("at", "id")).isEqualTo(" AND at <= :afterAt");
        assertThat(bound.params()).containsOnlyKeys("afterAt");
    }

    @Test
    void boundForTheCursorsOwnSourceBreaksTheTieOnTheId() {
        Bound bound = new ActivityCursor(AT, "workflow", ID).boundFor("workflow");

        assertThat(bound.mode()).isEqualTo(Bound.Mode.TIE_BREAK);
        assertThat(bound.clause("r.started_at", "r.id"))
                .isEqualTo(" AND (r.started_at, r.id) < (:afterAt, :afterId)");
        assertThat(bound.params()).containsEntry("afterId", ID).containsKey("afterAt");
    }

    @Test
    void withoutACursorNothingIsBounded() {
        Bound bound = ActivityCursor.boundFor(null, "auth");

        assertThat(bound.mode()).isEqualTo(Bound.Mode.NONE);
        assertThat(bound.clause("at", "id")).isEmpty();
        assertThat(bound.params()).isEmpty();
    }

    @Test
    void theBoundCarriesMicrosecondsIntoTheParameter() {
        Bound bound = new ActivityCursor(AT, "workflow", ID).boundFor("auth");

        assertThat(((java.sql.Timestamp) bound.params().get("afterAt")).toInstant()).isEqualTo(AT);
    }
}
