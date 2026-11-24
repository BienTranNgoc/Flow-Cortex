package com.flowcortex.billing.ledger;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The result-code table of each provider whose codes this system can read. Codes are not portable between
 * providers, so a provider without a table is refused rather than read through another's.
 */
final class ProviderCodeTables {

    private static final Map<String, Function<String, Optional<CodeRule>>> TABLES = Map.of(
            "momo", code -> Optional.ofNullable(MomoResultCodes.table().get(code)));

    private ProviderCodeTables() {
    }

    /**
     * What a code means: the money status it implies (empty when it says nothing about money) and who owns
     * the problem it reports ({@code customer}, {@code merchant}, {@code provider} or {@code none}).
     */
    record CodeRule(Optional<MoneyStatus> money, String owner) {
    }

    static Optional<Function<String, Optional<CodeRule>>> forProvider(String provider) {
        return Optional.ofNullable(provider).map(TABLES::get);
    }
}
