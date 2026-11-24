package com.flowcortex.billing.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.ledger.Timeline.Composed;
import com.flowcortex.billing.ledger.Timeline.HistoryEntry;
import com.flowcortex.billing.ledger.Timeline.TimelineEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineTest {

    private static final Instant T_0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant T_5 = Instant.parse("2026-11-20T10:00:05Z");
    private static final Instant T_9 = Instant.parse("2026-11-20T10:00:09Z");

    private static HistoryEntry opened(Instant at) {
        return new HistoryEntry(null, MoneyStatus.PENDING, "opened", at);
    }

    private static HistoryEntry moved(MoneyStatus from, MoneyStatus to, String code, Instant at) {
        return new HistoryEntry(from, to, "provider_code:" + code, at);
    }

    @Test
    void aPaidPaymentReadsOpenedThenPaidWithNoOutcome() {
        Composed composed = Timeline.compose(List.of(
                opened(T_0), moved(MoneyStatus.PENDING, MoneyStatus.SUCCEEDED, "0", T_5)));

        assertThat(composed.events()).containsExactly(
                new TimelineEvent(T_0, "opened", null),
                new TimelineEvent(T_5, "paid", null));
        assertThat(composed.outcomeCode()).isNull();
    }

    @Test
    void aPaymentThatExpiredThenPaidHasNoOutcomeCode() {
        Composed composed = Timeline.compose(List.of(
                opened(T_0),
                moved(MoneyStatus.PENDING, MoneyStatus.EXPIRED, "1005", T_5),
                moved(MoneyStatus.EXPIRED, MoneyStatus.SUCCEEDED, "0", T_9)));

        assertThat(composed.events()).containsExactly(
                new TimelineEvent(T_0, "opened", null),
                new TimelineEvent(T_5, "payment_expired", "1005"),
                new TimelineEvent(T_9, "paid", null));
        assertThat(composed.outcomeCode()).isNull();
    }

    @Test
    void aDeclinedPaymentCarriesTheProvidersCodeAsItsOutcome() {
        Composed composed = Timeline.compose(List.of(
                opened(T_0), moved(MoneyStatus.PENDING, MoneyStatus.FAILED, "1006", T_5)));

        assertThat(composed.events()).containsExactly(
                new TimelineEvent(T_0, "opened", null),
                new TimelineEvent(T_5, "payment_failed", "1006"));
        assertThat(composed.outcomeCode()).isEqualTo("1006");
    }

    @Test
    void anExpiredPaymentCarriesTheProvidersCodeAsItsOutcome() {
        Composed composed = Timeline.compose(List.of(
                opened(T_0), moved(MoneyStatus.PENDING, MoneyStatus.EXPIRED, "1005", T_5)));

        assertThat(composed.outcomeCode()).isEqualTo("1005");
    }

    @Test
    void entriesAtTheSameInstantFollowTheKindOrderWhateverTheInputOrder() {
        Composed composed = Timeline.compose(List.of(
                moved(MoneyStatus.EXPIRED, MoneyStatus.SUCCEEDED, "0", T_0),
                moved(MoneyStatus.PENDING, MoneyStatus.FAILED, "1006", T_0),
                opened(T_0),
                moved(MoneyStatus.PENDING, MoneyStatus.EXPIRED, "1005", T_0)));

        assertThat(composed.events()).extracting(TimelineEvent::kind)
                .containsExactly("opened", "paid", "payment_failed", "payment_expired");
    }

    @Test
    void entriesGivenOutOfOrderAreSortedByTime() {
        Composed composed = Timeline.compose(List.of(
                moved(MoneyStatus.PENDING, MoneyStatus.SUCCEEDED, "0", T_5), opened(T_0)));

        assertThat(composed.events()).extracting(TimelineEvent::kind).containsExactly("opened", "paid");
    }

    @Test
    void aRefundIsNotListed() {
        Composed composed = Timeline.compose(List.of(
                opened(T_0),
                moved(MoneyStatus.PENDING, MoneyStatus.SUCCEEDED, "0", T_5),
                new HistoryEntry(MoneyStatus.SUCCEEDED, MoneyStatus.REFUNDED, "refund", T_9)));

        assertThat(composed.events()).extracting(TimelineEvent::kind).containsExactly("opened", "paid");
        assertThat(composed.outcomeCode()).isNull();
    }

    @Test
    void aPendingMoveThatIsNotTheOpeningIsNotListed() {
        Composed composed = Timeline.compose(List.of(
                opened(T_0), moved(MoneyStatus.EXPIRED, MoneyStatus.PENDING, "1000", T_5)));

        assertThat(composed.events()).extracting(TimelineEvent::kind).containsExactly("opened");
    }

    @Test
    void aFailureWhoseReasonCarriesNoCodeListsNoCode() {
        Composed composed = Timeline.compose(List.of(
                opened(T_0), new HistoryEntry(MoneyStatus.PENDING, MoneyStatus.FAILED, "operator", T_5)));

        assertThat(composed.events()).last().isEqualTo(new TimelineEvent(T_5, "payment_failed", null));
        assertThat(composed.outcomeCode()).isNull();
    }

    @Test
    void noHistoryComposesNothing() {
        Composed composed = Timeline.compose(List.of());

        assertThat(composed.events()).isEmpty();
        assertThat(composed.outcomeCode()).isNull();
    }
}
