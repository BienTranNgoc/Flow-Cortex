package com.flowcortex.credit.application;

import com.flowcortex.credit.api.PricingUnit;
import com.flowcortex.credit.api.RateQuantity;
import com.flowcortex.credit.domain.RateCard;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Credits of one line: units times price times markup, rounded up to a whole credit. */
final class CreditPrice {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Absorbs the error of a repeating division (seconds over 60) so an exact price is never raised a credit. */
    private static final int NOISE_SCALE = 12;

    private CreditPrice() {
    }

    static long credits(PricingUnit unit, RateCard card, RateQuantity quantity) {
        BigDecimal markup = BigDecimal.ONE.add(card.markupPct().divide(HUNDRED));
        BigDecimal exact = unit.units(quantity).multiply(card.creditsPerUnit()).multiply(markup);
        if (exact.signum() <= 0) {
            return 0;
        }
        BigDecimal settled = exact.setScale(NOISE_SCALE, RoundingMode.HALF_UP);
        return Math.max(1, settled.setScale(0, RoundingMode.CEILING).longValueExact());
    }
}
