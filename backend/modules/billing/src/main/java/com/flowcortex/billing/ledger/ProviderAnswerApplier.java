package com.flowcortex.billing.ledger;

import com.flowcortex.billing.api.PaymentStatusChangedEvent;
import com.flowcortex.billing.ledger.LedgerDecision.Applied;
import com.flowcortex.billing.ledger.LedgerDecision.Decision;
import com.flowcortex.billing.ledger.LedgerDecision.TransactionSnapshot;
import com.flowcortex.billing.ledger.PaymentTransactionRepository.EventFacts;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way a provider's account of a payment reaches the payment, whether it arrived as a callback or was
 * asked for. It locks the payment, decides, and on a permitted move writes the status, the provider's facts
 * and one history entry and publishes the status change, all in the caller's transaction. A move to
 * {@code succeeded} also runs every {@link SettledPlanPurchase} there, so its effects commit with the move
 * whichever path brought the answer.
 */
@Component
public class ProviderAnswerApplier {

    private final PaymentTransactionRepository payments;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<SettledPlanPurchase> settledEffects;

    ProviderAnswerApplier(PaymentTransactionRepository payments, ApplicationEventPublisher events,
            ObjectProvider<SettledPlanPurchase> settledEffects) {
        this.payments = payments;
        this.events = events;
        this.settledEffects = settledEffects;
    }

    /** What the answer was decided against and what it was decided to be. */
    public record AnswerApplication(Optional<TransactionSnapshot> before, Decision decision, MoneyReading reading) {
    }

    /**
     * How the provider's answer reads, for a caller that classifies disagreements: the money status derived
     * from it, or why the derivation was refused, and the status its code alone implies.
     */
    public record MoneyReading(
            Optional<MoneyStatus> derived, Optional<String> derivationRefusal, Optional<MoneyStatus> codeMoney) {
    }

    /**
     * Applies {@code event} to the payment of {@code provider} it names. Must run inside the caller's
     * transaction: the payment's row lock is held until it ends, so two answers for one payment are decided
     * one after the other.
     *
     * @param evidenceRef what caused the move, recorded in the history and the status event
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AnswerApplication apply(String provider, Optional<PaymentProviderEvent> event, String evidenceRef,
            Instant now) {
        Optional<TransactionSnapshot> snapshot = event.flatMap(
                stated -> payments.lockByOrderRef(provider, stated.reference().operationRef()));
        Decision decision = LedgerDecision.decide(event, snapshot);
        if (decision instanceof Applied applied) {
            move(snapshot.orElseThrow().id(), applied, evidenceRef, now);
        }
        return new AnswerApplication(snapshot, decision, read(event, snapshot));
    }

    private void move(UUID paymentId, Applied applied, String evidenceRef, Instant now) {
        String resultCode = applied.facts().resultCode();
        if (payments.applyMove(paymentId, applied.from(), applied.to(), applied.facts(), now) != 1) {
            throw new IllegalStateException("The payment no longer holds the status the decision read");
        }
        payments.insertHistory(paymentId, applied.from(), applied.to(), "provider_code:" + resultCode, resultCode,
                evidenceRef, applied.facts().providerTimestamp(), now);
        if (applied.to() == MoneyStatus.SUCCEEDED) {
            settledEffects.orderedStream().forEach(effect -> effect.onSucceeded(paymentId, now));
        }
        EventFacts facts = payments.eventFacts(paymentId);
        events.publishEvent(new PaymentStatusChangedEvent(paymentId, facts.userId(), facts.kind(), facts.itemRef(),
                facts.amountVnd(), applied.from().wire(), applied.to().wire(), evidenceRef, now));
    }

    private static MoneyReading read(Optional<PaymentProviderEvent> event, Optional<TransactionSnapshot> snapshot) {
        if (event.isEmpty()) {
            return new MoneyReading(Optional.empty(), Optional.empty(), Optional.empty());
        }
        PaymentProviderEvent stated = event.get();
        Optional<MoneyStatus> codeMoney = MoneyDerivation.codeMoney(stated.provider(), stated.resultCode());
        if (snapshot.isEmpty()) {
            return new MoneyReading(Optional.empty(), Optional.empty(), codeMoney);
        }
        MoneyDerivation.Result result = MoneyDerivation.derive(stated.provider(), stated.resultCode(),
                stated.amount(), snapshot.get().amountVnd());
        if (result instanceof MoneyDerivation.Refused refused) {
            return new MoneyReading(Optional.empty(), Optional.of(refused.reason()), codeMoney);
        }
        return new MoneyReading(((MoneyDerivation.Derived) result).money(), Optional.empty(), codeMoney);
    }
}
