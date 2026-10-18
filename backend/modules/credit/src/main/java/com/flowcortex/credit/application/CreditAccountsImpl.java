package com.flowcortex.credit.application;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.credit.domain.CreditLedgerEntry;
import com.flowcortex.credit.domain.CreditWallet;
import com.flowcortex.credit.infrastructure.CreditLedgerRepository;
import com.flowcortex.credit.infrastructure.CreditWalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
    private final StepCosts costs;
    private final CreditWalletRepository wallets;
    private final CreditLedgerRepository ledger;
    private final Clock clock;

    CreditAccountsImpl(CreditProperties properties, CreditWalletRepository wallets, CreditLedgerRepository ledger,
            Clock clock) {
        this.enabled = properties.enabled();
        this.costs = new StepCosts(properties.stepCosts());
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
        return costs.estimate(stepTypes);
    }

    @Override
    public void requirePriced(Collection<String> stepTypes) {
        costs.requirePriced(stepTypes);
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
        long cost = costs.costOf(stepType);
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
