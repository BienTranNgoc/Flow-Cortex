package com.flowcortex.billing.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.catalog.TierOrder.OrderableTier;
import com.flowcortex.billing.catalog.TierOrder.TierOrderViolation;
import com.flowcortex.billing.catalog.TierOrder.TierOrderViolation.DuplicatePosition;
import com.flowcortex.billing.catalog.TierOrder.TierOrderViolation.PriceInversion;
import com.flowcortex.billing.catalog.TierOrder.TierOrderViolation.Unpriced;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TierOrderTest {

    private static OrderableTier tier(String id, int position, long monthlyPrice) {
        return new OrderableTier(id, position, OptionalLong.of(monthlyPrice));
    }

    private static OrderableTier unpriced(String id, int position) {
        return new OrderableTier(id, position, OptionalLong.empty());
    }

    private static List<OrderableTier> seededLadder() {
        return List.of(tier("free", 0, 0), tier("basic", 1, 849_000), tier("advanced", 2, 2_499_000),
                tier("professional", 3, 5_799_000));
    }

    @Test
    void theSeededLadderHasNoViolation() {
        assertThat(TierOrder.violations(seededLadder())).isEmpty();
    }

    @Test
    void noTiersHaveNoViolation() {
        assertThat(TierOrder.violations(List.of())).isEmpty();
    }

    @Test
    void twoTiersAtOnePositionAreOneDuplicateAndNoInversionForThePair() {
        List<OrderableTier> tiers = List.of(tier("free", 0, 0), tier("basic", 1, 849_000),
                tier("advanced", 1, 2_499_000), tier("professional", 3, 5_799_000));

        assertThat(TierOrder.violations(tiers)).containsExactly(new DuplicatePosition(1, List.of("advanced", "basic")));
    }

    @Test
    void aHigherPositionThatCostsLessIsAnInversion() {
        List<OrderableTier> tiers = List.of(tier("free", 0, 0), tier("basic", 1, 849_000),
                tier("advanced", 2, 799_000), tier("professional", 3, 5_799_000));

        assertThat(TierOrder.violations(tiers))
                .containsExactly(new PriceInversion("basic", 1, 849_000, "advanced", 2, 799_000));
    }

    @Test
    void equalPricesAreAnInversion() {
        List<OrderableTier> tiers = List.of(tier("basic", 1, 849_000), tier("advanced", 2, 849_000));

        assertThat(TierOrder.violations(tiers))
                .containsExactly(new PriceInversion("basic", 1, 849_000, "advanced", 2, 849_000));
    }

    @Test
    void aTierWithoutAMonthlyPriceIsUnpricedAndLeftOutOfTheComparison() {
        List<OrderableTier> tiers = List.of(tier("free", 0, 0), tier("basic", 1, 849_000), unpriced("team", 2),
                tier("advanced", 3, 2_499_000), unpriced("studio", 4));

        assertThat(TierOrder.violations(tiers)).containsExactly(new Unpriced("studio", 4), new Unpriced("team", 2));
    }

    @Test
    void aTierWithoutAPriceBetweenTwoPricedOnesDoesNotHideTheirInversion() {
        List<OrderableTier> tiers = List.of(tier("basic", 1, 849_000), unpriced("team", 2), tier("advanced", 3, 800_000));

        assertThat(TierOrder.violations(tiers)).containsExactly(new Unpriced("team", 2),
                new PriceInversion("basic", 1, 849_000, "advanced", 3, 800_000));
    }

    @Test
    void aDuplicateAndAnInversionElsewhereAreBothReportedDuplicatesFirst() {
        List<OrderableTier> tiers = List.of(tier("free", 0, 0), tier("basic", 1, 849_000),
                tier("advanced", 1, 2_499_000), tier("professional", 2, 800_000));

        List<TierOrderViolation> violations = TierOrder.violations(tiers);

        assertThat(violations).containsExactly(new DuplicatePosition(1, List.of("advanced", "basic")),
                new PriceInversion("basic", 1, 849_000, "professional", 2, 800_000));
    }

    @Test
    void theOrderOfTheInputDoesNotChangeTheResult() {
        List<OrderableTier> shuffled = List.of(tier("professional", 2, 800_000), tier("basic", 1, 849_000),
                tier("free", 0, 0), tier("advanced", 1, 2_499_000));

        assertThat(TierOrder.violations(shuffled)).containsExactly(
                new DuplicatePosition(1, List.of("advanced", "basic")),
                new PriceInversion("basic", 1, 849_000, "professional", 2, 800_000));
    }

    @Test
    void everyViolationHasItsKind() {
        assertThat(new DuplicatePosition(1, List.of("a", "b")).kind()).isEqualTo("duplicate_position");
        assertThat(new Unpriced("a", 1).kind()).isEqualTo("unpriced");
        assertThat(new PriceInversion("a", 1, 2, "b", 2, 1).kind()).isEqualTo("price_inversion");
    }

    @Test
    void anInversionIsDescribedWithBothTiersPositionsAndPrices() {
        String line = TierOrder.describe(new PriceInversion("basic", 1, 849_000, "advanced", 2, 799_000));

        assertThat(line).contains("price_inversion", "basic", "advanced", "849000", "799000", "1", "2");
    }

    @Test
    void aDuplicateAndAnUnpricedTierAreDescribedWithTheirPositions() {
        assertThat(TierOrder.describe(new DuplicatePosition(1, List.of("advanced", "basic"))))
                .contains("duplicate_position", "advanced", "basic", "1");
        assertThat(TierOrder.describe(new Unpriced("team", 4))).contains("unpriced", "team", "4");
    }
}
