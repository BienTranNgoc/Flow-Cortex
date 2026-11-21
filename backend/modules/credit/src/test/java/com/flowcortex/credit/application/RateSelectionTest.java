package com.flowcortex.credit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.credit.api.PricingUnit;
import com.flowcortex.credit.domain.RateCard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RateSelectionTest {

    private static final Instant OCT_18 = Instant.parse("2026-10-18T00:00:00Z");
    private static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");

    private static RateCard card(String ref, String price, Instant from, Instant to, Map<String, Object> dimensions) {
        return new RateCard(UUID.randomUUID(), ref, PricingUnit.PER_REQUEST, "flowcortex", null, dimensions,
                new BigDecimal(price), BigDecimal.ZERO, from, to);
    }

    @Test
    void aDatedPriceChangeSwitchesAtTheBoundaryInstant() {
        List<RateCard> rows = List.of(card("probe", "10", OCT_18, DEC_01, null), card("probe", "12", DEC_01, null, null));

        assertThat(RateSelection.active(rows, Instant.parse("2026-11-30T23:59:59Z"), null))
                .hasValueSatisfying(c -> assertThat(c.creditsPerUnit()).isEqualByComparingTo("10"));
        assertThat(RateSelection.active(rows, DEC_01, null))
                .hasValueSatisfying(c -> assertThat(c.creditsPerUnit()).isEqualByComparingTo("12"));
    }

    @Test
    void nothingPricesBeforeTheFirstRowOrWithoutRows() {
        assertThat(RateSelection.active(List.of(card("probe", "10", DEC_01, null, null)),
                Instant.parse("2026-11-15T00:00:00Z"), null)).isEmpty();
        assertThat(RateSelection.active(List.of(), DEC_01, null)).isEmpty();
    }

    @Test
    void aDimensionedKeyIsPricedOnlyByAnEqualDimensionRow() {
        List<RateCard> rows = List.of(card("video.gen", "5", OCT_18, null, Map.of("resolution", "720p")),
                card("video.gen", "8", OCT_18, null, Map.of("resolution", "1080p")));

        assertThat(RateSelection.active(rows, DEC_01, Map.of("resolution", "1080p")))
                .hasValueSatisfying(c -> assertThat(c.creditsPerUnit()).isEqualByComparingTo("8"));
        assertThat(RateSelection.active(rows, DEC_01, Map.of("resolution", "4k"))).isEmpty();
        assertThat(RateSelection.active(rows, DEC_01, null)).isEmpty();
        assertThat(RateSelection.active(rows, DEC_01, Map.of())).isEmpty();
    }

    @Test
    void aKeyWithoutDimensionsIgnoresTheCallOptions() {
        List<RateCard> rows = List.of(card("probe", "10", OCT_18, null, null));

        assertThat(RateSelection.active(rows, DEC_01, Map.of("seed", 7))).isPresent();
    }

    @Test
    void twoRowsActiveAtOneInstantAreRefusedNamingTheKey() {
        List<RateCard> rows = List.of(card("probe", "10", OCT_18, null, null), card("probe", "12", DEC_01, null, null));

        assertThatThrownBy(() -> RateSelection.active(rows, Instant.parse("2026-12-02T00:00:00Z"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe");
    }

    @Test
    void dimensionNumbersCompareByValue() {
        List<RateCard> rows = List.of(card("img", "3", OCT_18, null, Map.of("height", 720.0)));

        assertThat(RateSelection.active(rows, DEC_01, Map.of("height", 720))).isPresent();
        assertThat(RateSelection.active(rows, DEC_01, Map.of("height", 721))).isEmpty();
    }
}
