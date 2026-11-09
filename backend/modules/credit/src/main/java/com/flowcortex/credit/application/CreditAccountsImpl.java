package com.flowcortex.credit.application;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.credit.api.PricingUnit;
import com.flowcortex.credit.api.RateLine;
import com.flowcortex.credit.api.RateQuantity;
import com.flowcortex.credit.domain.CapabilityPricing;
import com.flowcortex.credit.domain.CapabilityPricing.Kind;
import com.flowcortex.credit.domain.CreditLedgerEntry;
import com.flowcortex.credit.domain.CreditWallet;
import com.flowcortex.credit.domain.RateCard;
import com.flowcortex.credit.infrastructure.CreditLedgerRepository;
import com.flowcortex.credit.infrastructure.CreditWalletRepository;
import com.flowcortex.credit.infrastructure.RateBookRepository;
import com.flowcortex.credit.infrastructure.RateBookRepository.PricingRows;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Charges and refunds join the caller's transaction, so a step's transition and its money commit together. */
@Service
class CreditAccountsImpl implements CreditAccounts {

    private static final Logger LOG = LoggerFactory.getLogger(CreditAccountsImpl.class);

    private final boolean enabled;
    private final RateBookRepository rates;
    private final CreditWalletRepository wallets;
    private final CreditLedgerRepository ledger;
    private final Clock clock;

    CreditAccountsImpl(CreditProperties properties, RateBookRepository rates, CreditWalletRepository wallets,
            CreditLedgerRepository ledger, Clock clock) {
        this.enabled = properties.enabled();
        this.rates = rates;
        this.wallets = wallets;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public long estimate(List<String> stepTypes) {
        if (stepTypes.isEmpty()) {
            return 0;
        }
        PricingRows book = rates.findPricing(new LinkedHashSet<>(stepTypes));
        Instant now = Instant.now(clock);
        Map<String, Long> priced = new HashMap<>();
        long total = 0;
        for (String type : stepTypes) {
            total += priced.computeIfAbsent(type, t -> stepPrice(t, book, now));
        }
        return total;
    }

    @Override
    public long estimate(String rateCardRef, RateQuantity quantity) {
        Objects.requireNonNull(quantity, "quantity");
        List<RateCard> rows = rates.findCards(List.of(rateCardRef)).getOrDefault(rateCardRef, List.of());
        Instant now = Instant.now(clock);
        Map<String, List<RateCard>> keys = new LinkedHashMap<>();
        for (RateCard row : rows) {
            if (row.model() == null && !row.hasDimensions()) {
                keys.computeIfAbsent(row.unit() + "/" + row.provider(), k -> new ArrayList<>()).add(row);
            }
        }
        List<RateCard> active = new ArrayList<>();
        for (List<RateCard> key : keys.values()) {
            RateSelection.active(key, now, null).ifPresent(active::add);
        }
        if (active.isEmpty()) {
            throw new IllegalStateException("No active rate card prices ref " + rateCardRef
                    + " without a model or dimensions");
        }
        if (active.size() > 1) {
            throw new IllegalStateException("Rate card ref " + rateCardRef + " is ambiguous: " + active.size()
                    + " providers or units are active; price it through estimateLines with the full key");
        }
        RateCard card = active.get(0);
        return CreditPrice.credits(card.unit(), card, quantity);
    }

    @Override
    public long estimateLines(List<RateLine> lines) {
        if (lines.isEmpty()) {
            return 0;
        }
        Map<String, List<RateCard>> cards = rates.findCards(
                new LinkedHashSet<>(lines.stream().map(RateLine::rateCardRef).toList()));
        Instant now = Instant.now(clock);
        long total = 0;
        for (RateLine line : lines) {
            List<RateCard> key = cards.getOrDefault(line.rateCardRef(), List.of()).stream()
                    .filter(row -> row.unit() == line.pricingUnit()
                            && row.provider().equals(line.providerKey())
                            && Objects.equals(row.model(), line.modelKey()))
                    .toList();
            RateCard card = RateSelection.active(key, now, line.dimensions()).orElseThrow(
                    () -> new IllegalStateException("No active rate card prices line " + line.rateCardRef() + " ("
                            + line.pricingUnit().wire() + ", " + line.providerKey()
                            + (line.modelKey() == null ? "" : ", " + line.modelKey())
                            + (line.dimensions() == null ? "" : ", " + line.dimensions()) + ")"));
            total += CreditPrice.credits(line.pricingUnit(), card, line.quantity());
        }
        return total;
    }

    @Override
    public void requirePriced(Collection<String> capabilities) {
        if (capabilities.isEmpty()) {
            return;
        }
        PricingRows book = rates.findPricing(new LinkedHashSet<>(capabilities));
        SortedMap<String, PricingCoverage.Verdict> unpriced = PricingCoverage.verdicts(capabilities,
                book.mappings(), book.cardsByRef(), Instant.now(clock));
        if (!unpriced.isEmpty()) {
            throw new IllegalStateException(PricingCoverage.message(unpriced));
        }
    }

    /** A step type's price at its unit's default quantity: free is 0; a mapped card is read as the startup check reads it. */
    private static long stepPrice(String type, PricingRows book, Instant now) {
        CapabilityPricing mapping = book.mappings().get(type);
        if (mapping == null) {
            throw new IllegalStateException("No price for step type " + type + ": no capability_pricing row");
        }
        if (mapping.kind() == Kind.FREE) {
            return 0;
        }
        if (mapping.kind() != Kind.RATE_CARD) {
            throw new IllegalStateException("Step type " + type + " has no fixed price: its capability_pricing kind is "
                    + mapping.kind().wire());
        }
        PricingUnit unit = mapping.unit();
        List<RateCard> key = book.cardsByRef().getOrDefault(mapping.ref(), List.of()).stream()
                .filter(row -> row.unit() == unit && row.provider().equals(mapping.provider())
                        && row.model() == null && !row.hasDimensions())
                .toList();
        RateCard card = RateSelection.active(key, now, null).orElseThrow(() -> new IllegalStateException(
                "No active rate_card row prices step type " + type + " (" + mapping.ref() + ", " + unit.wire() + ", "
                        + mapping.provider() + ")"));
        return CreditPrice.credits(unit, card, RateQuantity.DEFAULT);
    }

    @Override
    @Transactional(readOnly = true)
    public long balance(UUID userId) {
        return wallets.findById(userId).map(CreditWallet::getBalance).orElse(0L);
    }

    @Override
    @Transactional
    public boolean debitForStep(UUID userId, UUID runId, UUID runStepId, String stepType) {
        if (!enabled) {
            return true;
        }
        long cost = stepPrice(stepType, rates.findPricing(List.of(stepType)), Instant.now(clock));
        if (cost == 0) {
            return true;
        }
        if (ledger.debit(UUID.randomUUID(), userId, runId, runStepId, cost, Instant.now(clock)) == 1) {
            LOG.info("Credits: outcome=debit userId={} runId={} stepId={} amount={}", userId, runId, runStepId, cost);
            return true;
        }
        LOG.info("Credits: outcome=debit_refused userId={} runId={} stepId={} amount={}", userId, runId, runStepId,
                cost);
        return false;
    }

    @Override
    @Transactional
    public void refundStep(UUID runId, UUID runStepId, String errorCode) {
        if (!enabled) {
            return;
        }
        Optional<CreditLedgerEntry> debit = ledger.findByRunStepIdAndKind(runStepId, CreditLedgerEntry.DEBIT);
        if (debit.isEmpty()) {
            return;
        }
        UUID userId = debit.get().getUserId();
        long amount = debit.get().getAmount();
        if (!RefundPolicy.refunds(errorCode)) {
            LOG.info("Credits: outcome=refund_skipped_user_fault userId={} runId={} stepId={} amount={} code={}",
                    userId, runId, runStepId, amount, errorCode);
            return;
        }
        if (ledger.refund(UUID.randomUUID(), runStepId, Instant.now(clock)) == 1) {
            LOG.info("Credits: outcome=refund userId={} runId={} stepId={} amount={}", userId, runId, runStepId, amount);
        } else {
            LOG.info("Credits: outcome=refund_duplicate userId={} runId={} stepId={} amount={}", userId, runId,
                    runStepId, amount);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long chargedForRun(UUID runId) {
        return ledger.sumChargedForRun(runId);
    }
}
