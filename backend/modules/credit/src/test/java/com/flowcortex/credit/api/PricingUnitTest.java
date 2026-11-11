package com.flowcortex.credit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PricingUnitTest {

    private static boolean known(String unit, Map<String, ?> params) {
        return PricingUnit.quantityKnownFor(unit, params, List.of());
    }

    private static void assertUnits(PricingUnit unit, RateQuantity quantity, String expected) {
        assertThat(unit.units(quantity)).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void perImageIsKnownWhenTheCountIsAbsentOrDeclared() {
        assertThat(known("per_image", Map.of("numImages", 4))).isTrue();
        assertThat(known("per_image", Map.of("numImages", "10"))).isTrue();
        assertThat(known("per_image", Map.of())).isTrue();
        assertThat(known("per_image", Map.of("numImages", 0))).isFalse();
        assertThat(known("per_image", Map.of("numImages", "{count}"))).isFalse();
        assertThat(known("per_image", Map.of("numImages", -2))).isFalse();
        assertThat(known("per_image", Map.of("numImages", Double.NaN))).isFalse();
    }

    @Test
    void perSecondVideoNeedsADeclaredDuration() {
        assertThat(known("per_second_video", Map.of("durationSec", 8))).isTrue();
        assertThat(known("per_second_video", Map.of("durationSec", "5.5"))).isTrue();
        assertThat(known("per_second_video", Map.of())).isFalse();
        assertThat(known("per_second_video", Map.of("durationSec", "0"))).isFalse();
        assertThat(known("per_second_video", Map.of("durationSec", "5."))).isFalse();
    }

    @Test
    void perMinuteFollowsTheSameRuleAsPerSecondVideo() {
        assertThat(known("per_minute", Map.of("durationSec", 90))).isTrue();
        assertThat(known("per_minute", Map.of())).isFalse();
    }

    @Test
    void perThousandCharsNeedsAResolvedText() {
        assertThat(known("per_1k_chars", Map.of("text", "Xin chào"))).isTrue();
        assertThat(known("per_1k_chars", Map.of("text", "{step1.output}"))).isFalse();
        assertThat(known("per_1k_chars", Map.of("text", ""))).isFalse();
        assertThat(known("per_1k_chars", Map.of())).isFalse();
        assertThat(known("per_1k_chars", Map.of("text", 12))).isFalse();
    }

    @Test
    void anyWarningMakesTheQuantityUnknown() {
        assertThat(PricingUnit.quantityKnownFor("per_request", Map.of(), List.of("input image unreadable"))).isFalse();
        assertThat(PricingUnit.quantityKnownFor("per_request", Map.of(), List.of())).isTrue();
        assertThat(PricingUnit.quantityKnownFor("per_request", null, null)).isTrue();
    }

    @Test
    void perRequestAndPerOnboardingAreAlwaysKnown() {
        assertThat(known("per_request", Map.of())).isTrue();
        assertThat(known("per_onboarding", Map.of())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"per_minute_audio", "per_1m_tokens_in", "per_1m_tokens_out", "per_megapixel",
            "per_megapixel_second", "per_video", "per_hour"})
    void theOtherUnitsAreNeverKnown(String unit) {
        assertThat(known(unit, Map.of("durationSec", 30))).isFalse();
    }

    @Test
    void aMissingUnitIsUnknown() {
        assertThat(known(null, Map.of())).isFalse();
    }

    @Test
    void fromWireReadsTheTwelveWireValuesAndNothingElse() {
        assertThat(PricingUnit.values()).hasSize(12);
        for (PricingUnit unit : PricingUnit.values()) {
            assertThat(PricingUnit.fromWire(unit.wire())).contains(unit);
        }
        assertThat(PricingUnit.fromWire("per_hour")).isEmpty();
        assertThat(PricingUnit.fromWire(null)).isEmpty();
    }

    @Test
    void unitsDefaultWhenTheQuantityOmitsThem() {
        RateQuantity none = RateQuantity.DEFAULT;
        assertUnits(PricingUnit.PER_REQUEST, none, "1");
        assertUnits(PricingUnit.PER_ONBOARDING, none, "1");
        assertUnits(PricingUnit.PER_IMAGE, none, "1");
        assertUnits(PricingUnit.PER_VIDEO, none, "1");
        assertUnits(PricingUnit.PER_MEGAPIXEL, none, "1.048576");
        assertUnits(PricingUnit.PER_SECOND_VIDEO, none, "0");
        assertUnits(PricingUnit.PER_MINUTE, none, "0");
        assertUnits(PricingUnit.PER_MINUTE_AUDIO, none, "0");
        assertUnits(PricingUnit.PER_1K_CHARS, none, "0");
        assertUnits(PricingUnit.PER_MEGAPIXEL_SECOND, none, "0");
        assertUnits(PricingUnit.PER_1M_TOKENS_IN, none, "0");
        assertUnits(PricingUnit.PER_1M_TOKENS_OUT, none, "0");
    }

    @Test
    void unitsReadTheQuantityField() {
        RateQuantity q = new RateQuantity(3L, 4L, 2000L, 500L, new BigDecimal("90"), 2500L, 3_000_000L, 500_000L,
                new BigDecimal("7.5"));
        assertUnits(PricingUnit.PER_REQUEST, q, "3");
        assertUnits(PricingUnit.PER_IMAGE, q, "4");
        assertUnits(PricingUnit.PER_MEGAPIXEL, q, "1");
        assertUnits(PricingUnit.PER_SECOND_VIDEO, q, "90");
        assertUnits(PricingUnit.PER_MINUTE, q, "1.5");
        assertUnits(PricingUnit.PER_MINUTE_AUDIO, q, "1.5");
        assertUnits(PricingUnit.PER_1K_CHARS, q, "2.5");
        assertUnits(PricingUnit.PER_1M_TOKENS_IN, q, "3");
        assertUnits(PricingUnit.PER_1M_TOKENS_OUT, q, "0.5");
        assertUnits(PricingUnit.PER_MEGAPIXEL_SECOND, q, "7.5");
        assertUnits(PricingUnit.PER_ONBOARDING, q, "1");
        assertUnits(PricingUnit.PER_VIDEO, q, "1");
    }

    @ParameterizedTest
    @EnumSource(PricingUnit.class)
    void quantityParamsAreTheOnesThePredicateReads(PricingUnit unit) {
        Set<String> expected = switch (unit) {
            case PER_IMAGE -> Set.of("numImages");
            case PER_1K_CHARS -> Set.of("text");
            case PER_SECOND_VIDEO, PER_MINUTE -> Set.of("durationSec");
            default -> Set.of();
        };
        assertThat(unit.quantityParams()).isEqualTo(expected);
    }
}
