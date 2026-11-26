package com.flowcortex.billing.ledger;

import com.flowcortex.billing.ledger.ProviderCodeTables.CodeRule;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns what a provider stated into what this system will state. A code alone decides nothing: success is
 * derived from the code and a matching amount, and a refusal is a result with a reason, never an exception.
 */
final class MoneyDerivation {

    private MoneyDerivation() {
    }

    sealed interface Result permits Derived, Refused {
    }

    /** The code was read; {@code money} is empty when it says nothing about the payment's money. */
    record Derived(Optional<MoneyStatus> money, CodeRule rule) implements Result {
    }

    /** Nothing may be stated: {@code unknown_provider}, {@code no_result_code}, {@code unmapped_code}, ... */
    record Refused(String reason) implements Result {
    }

    /**
     * Checks run in the order that makes the next one meaningful: provider, code present, code mapped,
     * then the amount. The amount is compared whatever the code says; only a success code needs one stated.
     */
    static Result derive(String provider, String resultCode, Long statedAmount, long recordedAmount) {
        Optional<Function<String, Optional<CodeRule>>> table = ProviderCodeTables.forProvider(provider);
        if (table.isEmpty()) {
            return new Refused("unknown_provider");
        }
        if (resultCode == null) {
            return new Refused("no_result_code");
        }
        Optional<CodeRule> rule = table.get().apply(resultCode);
        if (rule.isEmpty()) {
            return new Refused("unmapped_code");
        }
        Optional<MoneyStatus> money = rule.get().money();
        if (statedAmount == null) {
            if (money.equals(Optional.of(MoneyStatus.SUCCEEDED))) {
                return new Refused("amount_unstated");
            }
            return new Derived(money, rule.get());
        }
        if (statedAmount != recordedAmount) {
            return new Refused("amount_mismatch");
        }
        return new Derived(money, rule.get());
    }

    /** Whether the provider's own table reads this code as a successful payment, whatever else is wrong. */
    static boolean meansSuccess(String provider, String resultCode) {
        return codeMoney(provider, resultCode).filter(MoneyStatus.SUCCEEDED::equals).isPresent();
    }

    /** The money status the provider's own table gives this code, empty when it has none or says nothing about money. */
    static Optional<MoneyStatus> codeMoney(String provider, String resultCode) {
        if (resultCode == null) {
            return Optional.empty();
        }
        return ProviderCodeTables.forProvider(provider)
                .flatMap(table -> table.apply(resultCode))
                .flatMap(CodeRule::money);
    }
}
