package com.flowcortex.billing.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.refund.TurnStanding.CountableRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnStandingTest {

    private static final Instant NOW = Instant.parse("2026-11-26T00:00:00Z");

    private static CountableRequest request(String status, Instant createdAt) {
        return new CountableRequest(status, createdAt);
    }

    @Test
    void cancelledAndRejectedRequestsHoldNoTurn() {
        Instant recent = Instant.parse("2026-11-01T08:00:00Z");
        List<Instant> counted = TurnStanding.counted(
                List.of(request("cancelled", recent), request("rejected", recent), request("pending", recent)),
                NOW, 6);

        TurnStanding standing = TurnStanding.of(counted, NOW, 2, 6, true);

        assertThat(standing.used()).isEqualTo(1);
        assertThat(standing.free()).isTrue();
        assertThat(standing.nextTurnAt()).isNull();
    }

    @Test
    void approvedAndAutoApprovedRequestsHoldATurn() {
        Instant recent = Instant.parse("2026-11-01T08:00:00Z");

        List<Instant> counted = TurnStanding.counted(
                List.of(request("approved", recent), request("auto_approved", recent)), NOW, 6);

        assertThat(counted).hasSize(2);
    }

    @Test
    void whenNoTurnIsFreeTheNextOneOpensAWindowAfterTheOldestRequest() {
        List<Instant> counted = List.of(Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-08-31T08:00:00Z"));

        TurnStanding standing = TurnStanding.of(counted, NOW, 2, 6, true);

        assertThat(standing.used()).isEqualTo(2);
        assertThat(standing.free()).isFalse();
        assertThat(standing.nextTurnAt()).isEqualTo(Instant.parse("2027-02-28T08:00:00Z"));
    }

    @Test
    void whenTheLimitWasLoweredUnderTheCountTheNextTurnWaitsForEnoughRequestsToSlideOut() {
        List<Instant> counted = List.of(Instant.parse("2026-11-01T08:00:00Z"), Instant.parse("2026-08-31T08:00:00Z"),
                Instant.parse("2026-10-01T08:00:00Z"));

        TurnStanding standing = TurnStanding.of(counted, NOW, 2, 6, true);

        assertThat(standing.used()).isEqualTo(3);
        assertThat(standing.free()).isFalse();
        assertThat(standing.nextTurnAt()).isEqualTo(Instant.parse("2027-04-01T08:00:00Z"));
    }

    @Test
    void aRequestSixMonthsAndASecondOldIsNotCounted() {
        Instant windowStart = Instant.parse("2026-05-26T00:00:00Z");

        List<Instant> counted = TurnStanding.counted(
                List.of(request("pending", windowStart.minusSeconds(1)), request("pending", windowStart),
                        request("pending", windowStart.plusSeconds(1))), NOW, 6);

        assertThat(counted).containsExactly(windowStart.plusSeconds(1));
    }

    @Test
    void whenTheLimitIsOffEveryRequestIsCountedAndATurnIsAlwaysFree() {
        Instant recent = Instant.parse("2026-11-01T08:00:00Z");
        List<Instant> counted = List.of(recent, recent, recent, recent, recent);

        TurnStanding standing = TurnStanding.of(counted, NOW, 2, 6, false);

        assertThat(standing.used()).isEqualTo(5);
        assertThat(standing.free()).isTrue();
        assertThat(standing.nextTurnAt()).isNull();
    }

    @Test
    void shiftingAMonthBackClampsTheDayToTheLastDayOfTheTargetMonth() {
        Instant now = Instant.parse("2026-03-31T00:00:00Z");

        List<Instant> counted = TurnStanding.counted(
                List.of(request("pending", Instant.parse("2026-02-28T00:00:00Z")),
                        request("pending", Instant.parse("2026-02-28T00:00:01Z"))), now, 1);

        assertThat(counted).containsExactly(Instant.parse("2026-02-28T00:00:01Z"));
    }

    @Test
    void theStandingCarriesTheLimitAndTheWindowItWasJudgedAgainst() {
        TurnStanding standing = TurnStanding.of(List.of(), NOW, 2, 6, true);

        assertThat(standing.enabled()).isTrue();
        assertThat(standing.limit()).isEqualTo(2);
        assertThat(standing.windowMonths()).isEqualTo(6);
    }
}
