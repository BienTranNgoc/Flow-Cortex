package com.flowcortex.credit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.credit.api.PricingUnit;
import com.flowcortex.credit.application.PricingCoverage.Reason;
import com.flowcortex.credit.application.PricingCoverage.Verdict;
import com.flowcortex.credit.domain.CapabilityPricing;
import com.flowcortex.credit.domain.CapabilityPricing.Kind;
import com.flowcortex.credit.domain.RateCard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PricingCoverageTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant START = Instant.parse("2026-10-18T00:00:00Z");
    private static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");

    private static CapabilityPricing rated(String capability, PricingUnit unit, String... params) {
        return new CapabilityPricing(capability, Kind.RATE_CARD, capability, unit, "flowcortex", List.of(params));
    }

    private static CapabilityPricing kind(String capability, Kind kind, String... params) {
        return new CapabilityPricing(capability, kind, null, null, null, List.of(params));
    }

    private static RateCard row(String ref, PricingUnit unit, Instant from, Instant to) {
        return new RateCard(UUID.randomUUID(), ref, unit, "flowcortex", null, null, BigDecimal.TEN, BigDecimal.ZERO,
                from, to);
    }

    private static SortedMap<String, Verdict> run(String capability, CapabilityPricing mapping, RateCard... rows) {
        Map<String, CapabilityPricing> mappings = mapping == null ? Map.of() : Map.of(capability, mapping);
        return PricingCoverage.verdicts(List.of(capability), mappings, Map.of(capability, List.of(rows)), NOW);
    }

    private static Reason only(SortedMap<String, Verdict> verdicts) {
        assertThat(verdicts).hasSize(1);
        return verdicts.values().iterator().next().reason();
    }

    @Test
    void aCapabilityWithoutAMappingHasNoPrice() {
        assertThat(only(run("translate", null))).isEqualTo(Reason.NO_MAPPING);
    }

    @Test
    void aSampledCapabilityIsRefused() {
        assertThat(only(run("iterate", kind("iterate", Kind.MC)))).isEqualTo(Reason.MC_KIND);
    }

    @Test
    void aFreeCapabilityListingAParameterIsRefusedNamingIt() {
        SortedMap<String, Verdict> verdicts = run("echo", kind("echo", Kind.FREE, "numImages"));
        assertThat(only(verdicts)).isEqualTo(Reason.INVALID_QUANTITY_PARAMS);
        assertThat(verdicts.get("echo").detail()).contains("numImages");
    }

    @Test
    void aParameterItsUnitDoesNotReadIsRefusedNamingIt() {
        SortedMap<String, Verdict> verdicts = run("video.gen",
                rated("video.gen", PricingUnit.PER_SECOND_VIDEO, "numImages"),
                row("video.gen", PricingUnit.PER_SECOND_VIDEO, START, null));
        assertThat(only(verdicts)).isEqualTo(Reason.INVALID_QUANTITY_PARAMS);
        assertThat(verdicts.get("video.gen").detail()).contains("numImages");
    }

    @Test
    void aMatchingParameterPasses() {
        assertThat(run("image.gen", rated("image.gen", PricingUnit.PER_IMAGE, "numImages"),
                row("image.gen", PricingUnit.PER_IMAGE, START, null))).isEmpty();
    }

    @Test
    void overlappingRowsAreRefusedNamingTheKey() {
        SortedMap<String, Verdict> verdicts = run("probe", rated("probe", PricingUnit.PER_REQUEST),
                row("probe", PricingUnit.PER_REQUEST, START, Instant.parse("2026-12-15T00:00:00Z")),
                row("probe", PricingUnit.PER_REQUEST, DEC_01, null));
        assertThat(only(verdicts)).isEqualTo(Reason.OVERLAPPING_ROWS);
        assertThat(verdicts.get("probe").detail()).contains("probe");
    }

    @Test
    void noActiveCardWhenTheFirstRowStartsLaterOrThereIsNone() {
        assertThat(only(run("probe", rated("probe", PricingUnit.PER_REQUEST),
                row("probe", PricingUnit.PER_REQUEST, DEC_01, null)))).isEqualTo(Reason.NO_ACTIVE_CARD);
        assertThat(only(run("probe", rated("probe", PricingUnit.PER_REQUEST)))).isEqualTo(Reason.NO_ACTIVE_CARD);
    }

    @Test
    void coverageEndsWhenTheClosedRowHasNoSuccessor() {
        assertThat(only(run("probe", rated("probe", PricingUnit.PER_REQUEST),
                row("probe", PricingUnit.PER_REQUEST, START, Instant.parse("2027-01-01T00:00:00Z")))))
                .isEqualTo(Reason.COVERAGE_ENDS);
    }

    @Test
    void aGapBetweenRowsIsCoverageEnding() {
        SortedMap<String, Verdict> verdicts = run("probe", rated("probe", PricingUnit.PER_REQUEST),
                row("probe", PricingUnit.PER_REQUEST, START, DEC_01),
                row("probe", PricingUnit.PER_REQUEST, Instant.parse("2026-12-02T00:00:00Z"), null));
        assertThat(only(verdicts)).isEqualTo(Reason.COVERAGE_ENDS);
        assertThat(verdicts.get("probe").detail()).contains("gap");
    }

    @Test
    void aChainOfRowsReachingAnOpenRowPasses() {
        assertThat(run("probe", rated("probe", PricingUnit.PER_REQUEST),
                row("probe", PricingUnit.PER_REQUEST, START, DEC_01),
                row("probe", PricingUnit.PER_REQUEST, DEC_01, null))).isEmpty();
    }

    @Test
    void aUnitWhoseQuantityIsNotFixedIsRefusedEvenWithAnOpenRow() {
        assertThat(only(run("video.gen", rated("video.gen", PricingUnit.PER_SECOND_VIDEO, "durationSec"),
                row("video.gen", PricingUnit.PER_SECOND_VIDEO, START, null)))).isEqualTo(Reason.QUANTITY_NOT_FIXED);
    }

    @Test
    void perImageWithoutParametersAndFreePass() {
        assertThat(run("image.gen", rated("image.gen", PricingUnit.PER_IMAGE),
                row("image.gen", PricingUnit.PER_IMAGE, START, null))).isEmpty();
        assertThat(run("APPROVAL_CHECKPOINT", kind("APPROVAL_CHECKPOINT", Kind.FREE))).isEmpty();
    }

    @Test
    void aCapabilityFailingTwoChecksGetsOnlyTheFirst() {
        assertThat(only(run("video.gen", rated("video.gen", PricingUnit.PER_SECOND_VIDEO, "numImages"))))
                .isEqualTo(Reason.INVALID_QUANTITY_PARAMS);
    }

    @Test
    void rowsOfAnotherProviderDoNotCountForTheMappedKey() {
        RateCard otherProvider = new RateCard(UUID.randomUUID(), "probe", PricingUnit.PER_REQUEST, "fal", null, null,
                BigDecimal.ONE, BigDecimal.ZERO, START, null);
        assertThat(run("probe", rated("probe", PricingUnit.PER_REQUEST),
                row("probe", PricingUnit.PER_REQUEST, START, null), otherProvider)).isEmpty();
    }

    @Test
    void twoUnpricedTypesGiveOneMessageInNameOrder() {
        Map<String, CapabilityPricing> mappings = Map.of("probe", rated("probe", PricingUnit.PER_REQUEST));
        Map<String, List<RateCard>> cards = Map.of("probe",
                List.of(row("probe", PricingUnit.PER_REQUEST, START, Instant.parse("2027-01-01T00:00:00Z"))));

        String message = PricingCoverage.message(
                PricingCoverage.verdicts(List.of("translate", "probe"), mappings, cards, NOW));

        assertThat(message).contains("capability_pricing").contains("rate_card");
        assertThat(message.indexOf("probe")).isPositive().isLessThan(message.indexOf("translate"));
        assertThat(message).contains("coverage ends").contains("no mapping");
    }
}
