package com.flowcortex.user.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogFilterTest {

    private static final Instant TEN = Instant.parse("2026-11-15T10:00:00Z");
    private static final Instant ELEVEN = Instant.parse("2026-11-15T11:00:00Z");

    private static AuditLogFilter search(String q) {
        return AuditLogFilter.of(q, null, null, null, null, null, null, null);
    }

    @Test
    void reversedRange() {
        assertThatThrownBy(() -> AuditLogFilter.of(null, null, null, null, null, null, ELEVEN, TEN))
                .isInstanceOfSatisfying(AuditLogFilter.Invalid.class,
                        ex -> assertThat(ex.errors()).containsOnlyKeys("from"));
    }

    @Test
    void emptyRange() {
        assertThatThrownBy(() -> AuditLogFilter.of(null, null, null, null, null, null, TEN, TEN))
                .isInstanceOf(AuditLogFilter.Invalid.class);
    }

    @Test
    void aRangeInOrderIsValid() {
        AuditLogFilter filter = AuditLogFilter.of(null, null, null, null, null, null, TEN, ELEVEN);

        assertThat(filter.from()).isEqualTo(TEN);
        assertThat(filter.to()).isEqualTo(ELEVEN);
    }

    @Test
    void twoCharacterSearch() {
        assertThatThrownBy(() -> search("ab")).isInstanceOfSatisfying(AuditLogFilter.Invalid.class,
                ex -> assertThat(ex.errors()).containsOnlyKeys("q"));
    }

    @Test
    void threeCharacterSearch() {
        assertThat(search("mai").q()).isEqualTo("mai");
    }

    @Test
    void searchOverTheMaximumLength() {
        assertThatThrownBy(() -> search("a".repeat(101))).isInstanceOf(AuditLogFilter.Invalid.class);
        assertThat(search("a".repeat(100)).q()).hasSize(100);
    }

    @Test
    void unknownStatus() {
        assertThatThrownBy(() -> AuditLogFilter.of(null, null, null, null, null, "error", null, null))
                .isInstanceOfSatisfying(AuditLogFilter.Invalid.class,
                        ex -> assertThat(ex.errors()).containsOnlyKeys("status"));
    }

    @Test
    void knownStatuses() {
        assertThat(AuditLogFilter.of(null, null, null, null, null, "success", null, null).status())
                .isEqualTo("success");
        assertThat(AuditLogFilter.of(null, null, null, null, null, "failed", null, null).status())
                .isEqualTo("failed");
    }

    @Test
    void everyBreachIsReportedTogether() {
        assertThatThrownBy(() -> AuditLogFilter.of("ab", null, null, null, null, "error", ELEVEN, TEN))
                .isInstanceOfSatisfying(AuditLogFilter.Invalid.class,
                        ex -> assertThat(ex.errors()).containsOnlyKeys("q", "status", "from"));
    }

    @Test
    void searchEscapesLikeWildcards() {
        assertThat(search("50%").pattern()).isEqualTo("%50\\%%");
        assertThat(search("a_b").pattern()).isEqualTo("%a\\_b%");
        assertThat(search("a\\b").pattern()).isEqualTo("%a\\\\b%");
    }

    @Test
    void searchPatternIsLowerCasedIndependentOfTheDefaultLocale() {
        assertThat(search("MAI.TRAN").pattern()).isEqualTo("%mai.tran%");
        assertThat(search("TITLE").pattern()).isEqualTo("%title%");
    }

    @Test
    void withoutASearchThereIsNoPattern() {
        assertThat(search(null).pattern()).isNull();
    }

    @Test
    void givenFiltersAreTheOnesSent() {
        UUID actor = UUID.fromString("5b2d0000-0000-4000-8000-000000000001");

        AuditLogFilter filter = AuditLogFilter.of(null, "user.update", null, null, actor, "failed", TEN, ELEVEN);

        assertThat(filter.givenAsJson(3).toString()).isEqualTo("{\"action\":\"user.update\",\"actorUserId\":\""
                + actor + "\",\"status\":\"failed\",\"from\":\"2026-11-15T10:00:00Z\","
                + "\"to\":\"2026-11-15T11:00:00Z\",\"rowCount\":3}");
        assertThat(search(null).givenAsJson(5).toString()).isEqualTo("{\"rowCount\":5}");
    }

    @Test
    void tamperedCursor() {
        assertThatThrownBy(() -> AuditLogCursor.decode("not-a-cursor")).isInstanceOfSatisfying(
                AuditLogFilter.Invalid.class, ex -> assertThat(ex.errors()).containsOnlyKeys("cursor"));
    }

    @Test
    void cursorWithABadPayload() {
        String notNumeric = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("abc:5b2d0000-0000-4000-8000-000000000001".getBytes());
        String notAUuid = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("123:xyz".getBytes());

        assertThatThrownBy(() -> AuditLogCursor.decode(notNumeric)).isInstanceOf(AuditLogFilter.Invalid.class);
        assertThatThrownBy(() -> AuditLogCursor.decode(notAUuid)).isInstanceOf(AuditLogFilter.Invalid.class);
        assertThatThrownBy(() -> AuditLogCursor.decode("")).isInstanceOf(AuditLogFilter.Invalid.class);
        assertThatThrownBy(() -> AuditLogCursor.decode("A".repeat(500))).isInstanceOf(AuditLogFilter.Invalid.class);
    }

    @Test
    void cursorRoundTripKeepsMicroseconds() {
        Instant at = Instant.parse("2026-11-15T10:00:00.123456Z");
        UUID id = UUID.fromString("5b2d0000-0000-4000-8000-000000000001");

        AuditLogCursor decoded = AuditLogCursor.decode(new AuditLogCursor(at, id).encode());

        assertThat(decoded.at()).isEqualTo(at);
        assertThat(decoded.id()).isEqualTo(id);
    }
}
