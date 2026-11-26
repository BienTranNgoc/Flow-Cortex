package com.flowcortex.billing.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ReceiptFormatTest {

    private static final Locale VI = Locale.forLanguageTag("vi");

    @Test
    void moneyGroupsWithDotsInBothLanguages() {
        assertThat(ReceiptFormat.vnd(100_000)).isEqualTo("100.000 ₫");
        assertThat(ReceiptFormat.vnd(9_091)).isEqualTo("9.091 ₫");
        assertThat(ReceiptFormat.vnd(0)).isEqualTo("0 ₫");
        assertThat(ReceiptFormat.vnd(1_234_567_890L)).isEqualTo("1.234.567.890 ₫");
    }

    @Test
    void aRateIsAPercentWithTheLanguagesDecimalMark() {
        assertThat(ReceiptFormat.percent(1_000, VI)).isEqualTo("10%");
        assertThat(ReceiptFormat.percent(850, VI)).isEqualTo("8,5%");
        assertThat(ReceiptFormat.percent(850, Locale.ENGLISH)).isEqualTo("8.5%");
        assertThat(ReceiptFormat.percent(0, VI)).isEqualTo("0%");
        assertThat(ReceiptFormat.percent(825, VI)).isEqualTo("8,25%");
    }

    @Test
    void thePaidTimeIsShownInVietnamTime() {
        assertThat(ReceiptFormat.paidAt(Instant.parse("2026-12-31T17:05:00Z"))).isEqualTo("01/01/2027 00:05 (GMT+7)");
    }
}
