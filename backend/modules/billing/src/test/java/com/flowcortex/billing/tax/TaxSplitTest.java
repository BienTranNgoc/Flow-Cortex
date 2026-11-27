package com.flowcortex.billing.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.billing.tax.TaxSplit.Split;
import org.junit.jupiter.api.Test;

class TaxSplitTest {

    @Test
    void aGrossThatDoesNotDivideRoundsTheBase() {
        assertThat(TaxSplit.inclusive(100_000, 1_000)).isEqualTo(new Split(90_909, 9_091));
        assertThat(TaxSplit.inclusive(99_000, 800)).isEqualTo(new Split(91_667, 7_333));
        assertThat(TaxSplit.inclusive(50_000, 1_000)).isEqualTo(new Split(45_455, 4_545));
        assertThat(TaxSplit.inclusive(50_000, 800)).isEqualTo(new Split(46_296, 3_704));
    }

    @Test
    void aGrossThatDividesSplitsEvenly() {
        assertThat(TaxSplit.inclusive(11_000, 1_000)).isEqualTo(new Split(10_000, 1_000));
        assertThat(TaxSplit.inclusive(99_000, 1_000)).isEqualTo(new Split(90_000, 9_000));
    }

    @Test
    void anExactHalfRoundsTheBaseUp() {
        assertThat(TaxSplit.inclusive(21, 10_000)).isEqualTo(new Split(11, 10));
        assertThat(TaxSplit.inclusive(1_001, 10_000)).isEqualTo(new Split(501, 500));
    }

    @Test
    void aZeroRateHasNoTax() {
        assertThat(TaxSplit.inclusive(50_000, 0)).isEqualTo(new Split(50_000, 0));
    }

    @Test
    void aZeroGrossSplitsIntoZeros() {
        assertThat(TaxSplit.inclusive(0, 1_000)).isEqualTo(new Split(0, 0));
    }

    @Test
    void theLargestChargeAtAHundredPercentSplitsInHalf() {
        assertThat(TaxSplit.inclusive(50_000_000, 10_000)).isEqualTo(new Split(25_000_000, 25_000_000));
    }

    @Test
    void baseAndTaxAlwaysSumToTheGross() {
        for (int rate : new int[] {800, 1_000}) {
            for (long gross = 1; gross <= 20_000; gross++) {
                Split split = TaxSplit.inclusive(gross, rate);
                assertThat(split.baseVnd() + split.taxVnd()).as("gross %d at %d bps", gross, rate).isEqualTo(gross);
                assertThat(split.taxVnd()).isNotNegative();
            }
        }
    }

    @Test
    void aNegativeGrossOrRateIsRefused() {
        assertThatThrownBy(() -> TaxSplit.inclusive(-1, 1_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaxSplit.inclusive(1_000, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGrossBeyondWhatLongArithmeticHoldsOverflowsLoudly() {
        assertThatThrownBy(() -> TaxSplit.inclusive(Long.MAX_VALUE, 1_000)).isInstanceOf(ArithmeticException.class);
    }
}
