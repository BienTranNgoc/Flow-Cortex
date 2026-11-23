package com.flowcortex.billing.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HistoryQueryTest {

    private static final String PAGINATION = "invalid_pagination";
    private static final String FLAG = "invalid_query_flag";

    @Test
    void noParametersTakeTheDefaults() {
        HistoryQuery query = HistoryQuery.parse(null, null, null);

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.settled()).isFalse();
    }

    @Test
    void theBoundsOfPageAndSizeAreAccepted() {
        assertThat(HistoryQuery.parse("0", null, null).page()).isZero();
        assertThat(HistoryQuery.parse("10000", null, null).page()).isEqualTo(10_000);
        assertThat(HistoryQuery.parse(null, "1", null).size()).isEqualTo(1);
        assertThat(HistoryQuery.parse(null, "100", null).size()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "10001", "1.5", "ten", " 1", "+1", "99999999999999999999"})
    void aPageThatIsNotAWholeNumberFromZeroTo10000IsRefused(String page) {
        assertThatThrownBy(() -> HistoryQuery.parse(page, null, null))
                .isInstanceOfSatisfying(InvalidTransactionQueryException.class,
                        e -> assertThat(e.code()).isEqualTo(PAGINATION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-5", "ten", "2.0"})
    void aSizeThatIsNotAWholeNumberFromOneTo100IsRefused(String size) {
        assertThatThrownBy(() -> HistoryQuery.parse(null, size, null))
                .isInstanceOfSatisfying(InvalidTransactionQueryException.class,
                        e -> assertThat(e.code()).isEqualTo(PAGINATION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "TRUE", "2", " "})
    void aFlagOtherThanTrueFalseOneOrZeroIsRefused(String flag) {
        assertThatThrownBy(() -> HistoryQuery.parse(null, null, flag))
                .isInstanceOfSatisfying(InvalidTransactionQueryException.class,
                        e -> assertThat(e.code()).isEqualTo(FLAG));
    }

    @Test
    void theFourFlagSpellingsAreRead() {
        assertThat(HistoryQuery.parse(null, null, "true").settled()).isTrue();
        assertThat(HistoryQuery.parse(null, null, "1").settled()).isTrue();
        assertThat(HistoryQuery.parse(null, null, "false").settled()).isFalse();
        assertThat(HistoryQuery.parse(null, null, "0").settled()).isFalse();
    }

    @Test
    void anEmptyValueTakesTheDefault() {
        HistoryQuery query = HistoryQuery.parse("", "", "");

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.settled()).isFalse();
    }

    @Test
    void aBadPageIsReportedBeforeABadFlag() {
        assertThatThrownBy(() -> HistoryQuery.parse("-1", null, "yes"))
                .isInstanceOfSatisfying(InvalidTransactionQueryException.class,
                        e -> assertThat(e.code()).isEqualTo(PAGINATION));
    }
}
