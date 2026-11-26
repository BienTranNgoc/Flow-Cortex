package com.flowcortex.billing.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AmountInWordsTest {

    @Test
    void zeroReadsAsNoDong() {
        assertThat(AmountInWords.vietnamese(0)).isEqualTo("Không đồng");
    }

    @Test
    void unitsTensAndTheirVariants() {
        assertThat(AmountInWords.vietnamese(1)).isEqualTo("Một đồng");
        assertThat(AmountInWords.vietnamese(5)).isEqualTo("Năm đồng");
        assertThat(AmountInWords.vietnamese(10)).isEqualTo("Mười đồng");
        assertThat(AmountInWords.vietnamese(11)).isEqualTo("Mười một đồng");
        assertThat(AmountInWords.vietnamese(15)).isEqualTo("Mười lăm đồng");
        assertThat(AmountInWords.vietnamese(21)).isEqualTo("Hai mươi mốt đồng");
        assertThat(AmountInWords.vietnamese(25)).isEqualTo("Hai mươi lăm đồng");
    }

    @Test
    void aZeroTensDigitAfterHundredsReadsLe() {
        assertThat(AmountInWords.vietnamese(100)).isEqualTo("Một trăm đồng");
        assertThat(AmountInWords.vietnamese(105)).isEqualTo("Một trăm lẻ năm đồng");
    }

    @Test
    void everyGroupAfterTheFirstIsReadInFull() {
        assertThat(AmountInWords.vietnamese(1_005_000)).isEqualTo("Một triệu không trăm lẻ năm nghìn đồng");
        assertThat(AmountInWords.vietnamese(1_000_005)).isEqualTo("Một triệu không trăm lẻ năm đồng");
        assertThat(AmountInWords.vietnamese(1_000_000_005)).isEqualTo("Một tỷ không trăm lẻ năm đồng");
    }

    @Test
    void anAllZeroGroupIsSkipped() {
        assertThat(AmountInWords.vietnamese(1_000_000)).isEqualTo("Một triệu đồng");
        assertThat(AmountInWords.vietnamese(1_000_000_000)).isEqualTo("Một tỷ đồng");
    }

    @Test
    void realReceiptAmounts() {
        assertThat(AmountInWords.vietnamese(1_000)).isEqualTo("Một nghìn đồng");
        assertThat(AmountInWords.vietnamese(99_000)).isEqualTo("Chín mươi chín nghìn đồng");
        assertThat(AmountInWords.vietnamese(100_000)).isEqualTo("Một trăm nghìn đồng");
        assertThat(AmountInWords.vietnamese(299_000)).isEqualTo("Hai trăm chín mươi chín nghìn đồng");
        assertThat(AmountInWords.vietnamese(91_667)).isEqualTo("Chín mươi mốt nghìn sáu trăm sáu mươi bảy đồng");
        assertThat(AmountInWords.vietnamese(7_333)).isEqualTo("Bảy nghìn ba trăm ba mươi ba đồng");
    }

    @Test
    void theLargerScalesReadAsXoodReadsThem() {
        assertThat(AmountInWords.vietnamese(1_000_000_000_000L)).isEqualTo("Một nghìn tỷ đồng");
        assertThat(AmountInWords.vietnamese(1_000_000_000_000_000L)).isEqualTo("Một triệu tỷ đồng");
    }

    @Test
    void aNegativeAmountOrOneBeyondTheScalesIsRefused() {
        assertThatThrownBy(() -> AmountInWords.vietnamese(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AmountInWords.vietnamese(1_000_000_000_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
