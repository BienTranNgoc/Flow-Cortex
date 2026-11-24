package com.flowcortex.billing.ledger;

import com.flowcortex.billing.ledger.MoneyDerivation.Derived;
import com.flowcortex.billing.ledger.MoneyTransitions.Apply;
import com.flowcortex.billing.ledger.MoneyTransitions.Noop;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What a provider event does to a payment, decided from the two values alone: nothing is read or written
 * here, so the same rule serves every source of events.
 */
public final class LedgerDecision {

    private LedgerDecision() {
    }

    /** The payment as the decision needs to see it. */
    public record TransactionSnapshot(UUID id, MoneyStatus status, long amountVnd, Instant providerStatusAt) {
    }

    /** What the provider stated that is kept on the payment when a move is applied. */
    public record ProviderFacts(
            String resultCode,
            String message,
            String providerTransactionId,
            String payType,
            Instant providerTimestamp,
            String owner) {
    }

    public sealed interface Decision permits Applied, Skipped, Refused {
    }

    /** Move the payment from {@code from} to {@code to} and keep {@code facts}. */
    public record Applied(MoneyStatus from, MoneyStatus to, ProviderFacts facts) implements Decision {
    }

    /** Nothing to change, and nothing wrong: {@code already_in_state} or {@code no_money_axis}. */
    public record Skipped(String reason) implements Decision {
    }

    /**
     * The event must not change the payment. {@code derivedSuccess} is true when the provider's own table
     * reads its code as a successful payment, so money may have been taken that the payment does not show.
     */
    public record Refused(String reason, boolean derivedSuccess) implements Decision {
    }

    /**
     * Event readability comes first, because the payment can only be found through the event; then the
     * payment, then what the event means for money, its order among earlier events, and the move itself.
     */
    public static Decision decide(Optional<PaymentProviderEvent> event, Optional<TransactionSnapshot> snapshot) {
        if (event.isEmpty()) {
            return new Refused("event_unreadable", false);
        }
        PaymentProviderEvent stated = event.get();
        boolean success = MoneyDerivation.meansSuccess(stated.provider(), stated.resultCode());
        if (snapshot.isEmpty()) {
            return new Refused("transaction_not_found", success);
        }
        TransactionSnapshot payment = snapshot.get();

        MoneyDerivation.Result result =
                MoneyDerivation.derive(stated.provider(), stated.resultCode(), stated.amount(), payment.amountVnd());
        if (result instanceof MoneyDerivation.Refused refused) {
            return new Refused(refused.reason(), success);
        }
        Derived derived = (Derived) result;
        if (derived.money().isEmpty()) {
            return new Skipped("no_money_axis");
        }
        Optional<String> outOfOrder = EventOrdering.refusal(stated.providerTimestamp(), payment.providerStatusAt());
        if (outOfOrder.isPresent()) {
            return new Refused(outOfOrder.get(), success);
        }

        MoneyStatus target = derived.money().get();
        MoneyTransitions.Verdict verdict = MoneyTransitions.evaluate(payment.status(), target);
        if (verdict instanceof MoneyTransitions.Refused refused) {
            return new Refused(refused.reason(), success);
        }
        if (verdict instanceof Noop) {
            return new Skipped("already_in_state");
        }
        MoneyStatus from = ((Apply) verdict).from();
        return new Applied(from, target, new ProviderFacts(
                stated.resultCode(),
                stated.resultMessage(),
                stated.reference().providerTransactionId(),
                stated.providerPayType(),
                stated.providerTimestamp(),
                derived.rule().owner()));
    }
}
