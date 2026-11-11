package com.flowcortex.credit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.PricingUnit;
import com.flowcortex.credit.api.RateLine;
import com.flowcortex.credit.api.RateQuantity;
import com.flowcortex.credit.domain.CapabilityPricing;
import com.flowcortex.credit.domain.CapabilityPricing.Kind;
import com.flowcortex.credit.domain.CreditLedgerEntry;
import com.flowcortex.credit.domain.RateCard;
import com.flowcortex.credit.infrastructure.CreditLedgerRepository;
import com.flowcortex.credit.infrastructure.CreditWalletRepository;
import com.flowcortex.credit.infrastructure.RateBookRepository;
import com.flowcortex.credit.infrastructure.RateBookRepository.PricingRows;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreditAccountsImplTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private static final Instant SINCE = Instant.parse("2026-10-18T00:00:00Z");

    private final RateBookRepository rates = mock(RateBookRepository.class);
    private final CreditWalletRepository wallets = mock(CreditWalletRepository.class);
    private final CreditLedgerRepository ledger = mock(CreditLedgerRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID stepId = UUID.randomUUID();

    @BeforeEach
    void seededRates() {
        Map<String, CapabilityPricing> mappings = new HashMap<>();
        Map<String, List<RateCard>> cards = new HashMap<>();
        seed(mappings, cards, "probe", 10);
        seed(mappings, cards, "extract_audio", 20);
        mappings.put("echo", new CapabilityPricing("echo", Kind.FREE, null, null, null, List.of()));
        when(rates.findPricing(anyCollection())).thenReturn(new PricingRows(mappings, cards));
    }

    private CreditAccountsImpl accounts(boolean enabled) {
        return new CreditAccountsImpl(new CreditProperties(enabled), rates, wallets, ledger, clock);
    }

    private static void seed(Map<String, CapabilityPricing> mappings, Map<String, List<RateCard>> cards, String ref,
            int credits) {
        mappings.put(ref, new CapabilityPricing(ref, Kind.RATE_CARD, ref, PricingUnit.PER_REQUEST, "flowcortex",
                List.of()));
        cards.put(ref, List.of(card(ref, PricingUnit.PER_REQUEST, "flowcortex", credits, SINCE)));
    }

    private static RateCard card(String ref, PricingUnit unit, String provider, double credits, Instant from) {
        return new RateCard(UUID.randomUUID(), ref, unit, provider, null, null, BigDecimal.valueOf(credits),
                BigDecimal.ZERO, from, null);
    }

    private void givenMapping(CapabilityPricing mapping, List<RateCard> cardsOfRef) {
        Map<String, List<RateCard>> cards = mapping.ref() == null ? Map.of() : Map.of(mapping.ref(), cardsOfRef);
        when(rates.findPricing(anyCollection()))
                .thenReturn(new PricingRows(Map.of(mapping.capability(), mapping), cards));
    }

    @Test
    void reportsTheSwitchAndPricesStepTypesThroughTheMapping() {
        CreditAccountsImpl accounts = accounts(true);

        assertThat(accounts.enabled()).isTrue();
        assertThat(accounts(false).enabled()).isFalse();
        assertThat(accounts.estimate(List.of("probe", "extract_audio"))).isEqualTo(30);
        assertThat(accounts.estimate(List.of("probe", "echo"))).isEqualTo(10);
        assertThatThrownBy(() -> accounts.estimate(List.of("probe", "render_video")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("render_video");
    }

    @Test
    void anEmptyTypeListCostsNothingWithoutReadingTheRates() {
        assertThat(accounts(true).estimate(List.of())).isZero();

        verify(rates, never()).findPricing(any());
    }

    @Test
    void anUnmappedTypeIsNamedWithItsReasonAndTheTablesAtStartup() {
        CreditAccountsImpl accounts = accounts(true);

        accounts.requirePriced(List.of("probe", "echo"));
        assertThatThrownBy(() -> accounts.requirePriced(List.of("probe", "render_video")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("render_video (no mapping)")
                .hasMessageContaining("capability_pricing");
    }

    @Test
    void aTypeMappedToSampledPricingCannotBePriced() {
        givenMapping(new CapabilityPricing("sampled", Kind.MC, null, null, null, List.of()), List.of());

        assertThatThrownBy(() -> accounts(true).estimate(List.of("sampled")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sampled");
    }

    @Test
    void aTypeWhoseOnlyCardStartsInTheFutureCannotBePriced() {
        givenMapping(new CapabilityPricing("later", Kind.RATE_CARD, "later", PricingUnit.PER_REQUEST, "flowcortex",
                List.of()), List.of(card("later", PricingUnit.PER_REQUEST, "flowcortex", 10, NOW.plusSeconds(60))));

        assertThatThrownBy(() -> accounts(true).estimate(List.of("later")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("later");
    }

    @Test
    void aRefWithOneKeyIsPricedByItsQuantityAndAnAmbiguousOrUnknownRefThrowsNamingIt() {
        RateCard perImage = card("image.gen", PricingUnit.PER_IMAGE, "gemini", 6, SINCE);
        RateCard videoA = card("video.gen", PricingUnit.PER_SECOND_VIDEO, "veo", 1, SINCE);
        RateCard videoB = card("video.gen", PricingUnit.PER_SECOND_VIDEO, "kling", 2, SINCE);
        when(rates.findCards(anyCollection())).thenAnswer(call -> Map.of(
                "image.gen", List.of(perImage), "video.gen", List.of(videoA, videoB)));
        CreditAccountsImpl accounts = accounts(true);

        assertThat(accounts.estimate("image.gen", RateQuantity.DEFAULT)).isEqualTo(6);
        assertThat(accounts.estimate("image.gen",
                new RateQuantity(null, 3L, null, null, null, null, null, null, null))).isEqualTo(18);
        assertThatThrownBy(() -> accounts.estimate("video.gen", RateQuantity.DEFAULT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("video.gen")
                .hasMessageContaining("ambiguous");
        assertThatThrownBy(() -> accounts.estimate("nothing", RateQuantity.DEFAULT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing");
    }

    @Test
    void linesAreSummedAndAnUnpricedLineThrowsNamingItsRef() {
        RateCard perRequest = card("text", PricingUnit.PER_REQUEST, "gemini", 4, SINCE);
        RateCard perSecond = card("audio", PricingUnit.PER_SECOND_VIDEO, "worker", 1, SINCE);
        when(rates.findCards(anyCollection())).thenReturn(Map.of(
                "text", List.of(perRequest), "audio", List.of(perSecond)));
        CreditAccountsImpl accounts = accounts(true);
        RateLine request = new RateLine("text", PricingUnit.PER_REQUEST, "gemini", null, null, RateQuantity.DEFAULT);
        RateLine seconds = new RateLine("audio", PricingUnit.PER_SECOND_VIDEO, "worker", null, null,
                new RateQuantity(null, null, null, null, BigDecimal.TEN, null, null, null, null));
        RateLine unpriced = new RateLine("video.gen", PricingUnit.PER_SECOND_VIDEO, "veo", null, null,
                RateQuantity.DEFAULT);

        assertThat(accounts.estimateLines(List.of(request, seconds))).isEqualTo(14);
        assertThat(accounts.estimateLines(List.of())).isZero();
        assertThatThrownBy(() -> accounts.estimateLines(List.of(request, unpriced)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("video.gen");
    }

    @Test
    void aStepIsChargedOnlyWhenTheLedgerAcceptsTheDebit() {
        when(ledger.debit(any(), eq(userId), eq(runId), eq(stepId), eq(10L), eq(NOW))).thenReturn(1);

        assertThat(accounts(true).debitForStep(userId, runId, stepId, "probe")).isTrue();
    }

    @Test
    void aStepTheBalanceCannotCoverIsRefusedWithoutWriting() {
        when(ledger.debit(any(), eq(userId), eq(runId), eq(stepId), eq(20L), eq(NOW))).thenReturn(0);

        assertThat(accounts(true).debitForStep(userId, runId, stepId, "extract_audio")).isFalse();
    }

    @Test
    void aFreeStepAndADisabledSwitchAllowTheStepWithoutALedgerRow() {
        assertThat(accounts(true).debitForStep(userId, runId, stepId, "echo")).isTrue();
        assertThat(accounts(false).debitForStep(userId, runId, stepId, "probe")).isTrue();

        verify(ledger, never()).debit(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void aStepWithNoPriceIsNeitherChargedNorAllowed() {
        assertThatThrownBy(() -> accounts(true).debitForStep(userId, runId, stepId, "render_video"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("render_video");

        verify(ledger, never()).debit(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void aSystemFailureAsksTheLedgerToRefundTheStepsDebitAndTheLedgerKeepsItToOnce() {
        givenDebit(20L);
        when(ledger.refund(any(), eq(stepId), eq(NOW))).thenReturn(1).thenReturn(0);

        accounts(true).refundStep(runId, stepId, "lease_expired");
        accounts(true).refundStep(runId, stepId, "lease_expired");

        verify(ledger, times(2)).refund(any(), eq(stepId), eq(NOW));
    }

    @Test
    void aFailureTheMemberCausedIsNotRefunded() {
        givenDebit(20L);

        accounts(true).refundStep(runId, stepId, "INSUFFICIENT_CREDITS");

        verify(ledger, never()).refund(any(), any(), any());
    }

    @Test
    void aStepThatWasNeverChargedAndADisabledSwitchRefundNothing() {
        when(ledger.findByRunStepIdAndKind(stepId, CreditLedgerEntry.DEBIT)).thenReturn(Optional.empty());

        accounts(true).refundStep(runId, stepId, "run_timeout");
        accounts(false).refundStep(runId, stepId, "run_timeout");

        verify(ledger, never()).refund(any(), any(), any());
    }

    private void givenDebit(long amount) {
        CreditLedgerEntry debit = mock(CreditLedgerEntry.class);
        when(debit.getUserId()).thenReturn(userId);
        when(debit.getAmount()).thenReturn(amount);
        when(ledger.findByRunStepIdAndKind(stepId, CreditLedgerEntry.DEBIT)).thenReturn(Optional.of(debit));
    }
}
