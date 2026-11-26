package com.flowcortex.billing.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyDerivationTest {

    private static final long RECORDED = 50_000L;

    private static MoneyDerivation.Derived derived(MoneyDerivation.Result result) {
        assertThat(result).isInstanceOf(MoneyDerivation.Derived.class);
        return (MoneyDerivation.Derived) result;
    }

    @Test
    void successForTheRecordedAmountDerivesSucceeded() {
        MoneyDerivation.Derived result = derived(MoneyDerivation.derive("momo", "0", 50_000L, RECORDED));

        assertThat(result.money()).contains(MoneyStatus.SUCCEEDED);
        assertThat(result.rule().owner()).isEqualTo("none");
    }

    @Test
    void successForAnotherAmountIsRefusedAsAMismatch() {
        assertThat(MoneyDerivation.derive("momo", "0", 5_000L, RECORDED))
                .isEqualTo(new MoneyDerivation.Refused("amount_mismatch"));
    }

    @Test
    void aDeclineForAnotherAmountIsRefusedAsAMismatchWhateverTheCode() {
        assertThat(MoneyDerivation.derive("momo", "1006", 5_000L, RECORDED))
                .isEqualTo(new MoneyDerivation.Refused("amount_mismatch"));
    }

    @Test
    void successWithNoAmountIsRefusedAsUnstated() {
        assertThat(MoneyDerivation.derive("momo", "0", null, RECORDED))
                .isEqualTo(new MoneyDerivation.Refused("amount_unstated"));
    }

    @Test
    void aDeclineWithNoAmountDerivesFailed() {
        MoneyDerivation.Derived result = derived(MoneyDerivation.derive("momo", "1006", null, RECORDED));

        assertThat(result.money()).contains(MoneyStatus.FAILED);
        assertThat(result.rule().owner()).isEqualTo("customer");
    }

    @Test
    void anExpiredQrDerivesExpired() {
        assertThat(derived(MoneyDerivation.derive("momo", "1005", 50_000L, RECORDED)).money())
                .contains(MoneyStatus.EXPIRED);
    }

    @Test
    void aCodeStillInFlightDerivesPending() {
        assertThat(derived(MoneyDerivation.derive("momo", "7000", null, RECORDED)).money())
                .contains(MoneyStatus.PENDING);
    }

    @Test
    void aCodeWithoutMoneyMeaningDerivesNoStatus() {
        MoneyDerivation.Derived result = derived(MoneyDerivation.derive("momo", "99", null, RECORDED));

        assertThat(result.money()).isEmpty();
        assertThat(result.rule().owner()).isEqualTo("provider");
    }

    @Test
    void aCodeNobodyMappedIsRefused() {
        assertThat(MoneyDerivation.derive("momo", "8888", 50_000L, RECORDED))
                .isEqualTo(new MoneyDerivation.Refused("unmapped_code"));
    }

    @Test
    void noResultCodeIsRefused() {
        assertThat(MoneyDerivation.derive("momo", null, 50_000L, RECORDED))
                .isEqualTo(new MoneyDerivation.Refused("no_result_code"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"onepay", "MoMo", ""})
    void aProviderWithoutATableIsRefused(String provider) {
        assertThat(MoneyDerivation.derive(provider, "0", 50_000L, RECORDED))
                .isEqualTo(new MoneyDerivation.Refused("unknown_provider"));
    }

    @Test
    void theProviderIsCheckedBeforeTheCode() {
        assertThat(MoneyDerivation.derive("onepay", null, null, RECORDED))
                .isEqualTo(new MoneyDerivation.Refused("unknown_provider"));
    }

    @Test
    void theCodeIsCheckedBeforeTheAmount() {
        assertThat(MoneyDerivation.derive("momo", "8888", 5_000L, RECORDED))
                .isEqualTo(new MoneyDerivation.Refused("unmapped_code"));
    }

    @Test
    void onlyMomoHasACodeTable() {
        assertThat(ProviderCodeTables.forProvider("momo")).isPresent();
        assertThat(ProviderCodeTables.forProvider("onepay")).isEmpty();
        assertThat(ProviderCodeTables.forProvider(null)).isEmpty();
        assertThat(ProviderCodeTables.forProvider("momo").orElseThrow().apply("1005").orElseThrow().money())
                .contains(MoneyStatus.EXPIRED);
        assertThat(ProviderCodeTables.forProvider("momo").orElseThrow().apply("8888")).isEmpty();
    }

    @Test
    void theMomoTableHoldsExactlyTheThirtyFiveCodesGroupedByMeaning() {
        Map<String, ProviderCodeTables.CodeRule> table = MomoResultCodes.table();

        assertThat(table).hasSize(35);
        assertThat(codesMeaning(table, Optional.of(MoneyStatus.SUCCEEDED))).containsExactly("0");
        assertThat(codesMeaning(table, Optional.of(MoneyStatus.PENDING)))
                .containsExactlyInAnyOrder("1000", "7000", "7002", "9000");
        assertThat(codesMeaning(table, Optional.of(MoneyStatus.FAILED))).containsExactlyInAnyOrder(
                "1001", "1002", "1003", "1004", "1006", "1007", "1017", "1026", "1080", "1081", "1088",
                "2001", "2007", "2012", "2019", "4001", "4002", "4010", "4100");
        assertThat(codesMeaning(table, Optional.of(MoneyStatus.EXPIRED))).containsExactly("1005");
        assertThat(codesMeaning(table, Optional.empty())).containsExactlyInAnyOrder(
                "10", "11007", "13", "20", "2015", "2016", "40", "41", "98", "99");
    }

    @Test
    void everyEntryNamesOneOfTheFourOwners() {
        Set<String> owners = MomoResultCodes.table().values().stream()
                .map(ProviderCodeTables.CodeRule::owner)
                .collect(Collectors.toSet());

        assertThat(owners).isSubsetOf(List.of("customer", "merchant", "provider", "none"));
    }

    @Test
    void theTableDoesNotAcceptEdits() {
        Map<String, ProviderCodeTables.CodeRule> table = MomoResultCodes.table();
        ProviderCodeTables.CodeRule rule = table.get("0");

        assertThatThrownBy(() -> table.put("0", rule))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Set<String> codesMeaning(Map<String, ProviderCodeTables.CodeRule> table, Optional<MoneyStatus> money) {
        return table.entrySet().stream()
                .filter(entry -> entry.getValue().money().equals(money))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
