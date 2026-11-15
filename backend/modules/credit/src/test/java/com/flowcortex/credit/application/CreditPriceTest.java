package com.flowcortex.credit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.credit.api.PricingUnit;
import com.flowcortex.credit.api.RateQuantity;
import com.flowcortex.credit.domain.RateCard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreditPriceTest {

    private static long credits(PricingUnit unit, String price, String markup, RateQuantity quantity) {
        RateCard card = new RateCard(UUID.randomUUID(), "ref", unit, "flowcortex", null, null, new BigDecimal(price),
                new BigDecimal(markup), Instant.parse("2026-10-18T00:00:00Z"), null);
        return CreditPrice.credits(unit, card, quantity);
    }

    private static RateQuantity seconds(String sec) {
        return new RateQuantity(null, null, null, null, new BigDecimal(sec), null, null, null, null);
    }

    @Test
    void markupRaisesAHalfCreditToTheNextWholeCredit() {
        assertThat(credits(PricingUnit.PER_REQUEST, "10", "25", RateQuantity.DEFAULT)).isEqualTo(13);
    }

    @Test
    void perSecondPricesRoundUp() {
        assertThat(credits(PricingUnit.PER_SECOND_VIDEO, "0.4", "0", seconds("8"))).isEqualTo(4);
    }

    @Test
    void aTinyPositivePriceCostsOneCredit() {
        RateQuantity tokens = new RateQuantity(null, null, null, null, null, null, 1_000L, null, null);
        assertThat(credits(PricingUnit.PER_1M_TOKENS_IN, "2", "0", tokens)).isEqualTo(1);
    }

    @Test
    void zeroQuantityOrZeroPriceCostsNothing() {
        assertThat(credits(PricingUnit.PER_1K_CHARS, "3", "0", RateQuantity.DEFAULT)).isZero();
        assertThat(credits(PricingUnit.PER_REQUEST, "0", "50", RateQuantity.DEFAULT)).isZero();
    }

    @Test
    void anImageCountDefaultsToOne() {
        assertThat(credits(PricingUnit.PER_IMAGE, "6", "0", RateQuantity.DEFAULT)).isEqualTo(6);
    }

    @Test
    void anExactWholePriceIsNotRaised() {
        assertThat(credits(PricingUnit.PER_REQUEST, "20.000000", "0", RateQuantity.DEFAULT)).isEqualTo(20);
    }

    @Test
    void aRepeatingDivisionNeverTipsAnExactPriceUp() {
        assertThat(credits(PricingUnit.PER_MINUTE, "6", "0", seconds("10"))).isEqualTo(1);
    }
}
