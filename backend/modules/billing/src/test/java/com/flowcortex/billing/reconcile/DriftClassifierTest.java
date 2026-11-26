package com.flowcortex.billing.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.ledger.MoneyStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DriftClassifierTest {

    private static final String PROVIDER_TRANSACTION = "4088878653";

    private static ProviderAccount derived(MoneyStatus status) {
        return new ProviderAccount(Optional.of(status), Optional.empty(), Optional.of(status), PROVIDER_TRANSACTION);
    }

    private static ProviderAccount refused(String reason, MoneyStatus codeMoney, String transactionId) {
        return new ProviderAccount(Optional.empty(), Optional.of(reason), Optional.ofNullable(codeMoney),
                transactionId);
    }

    private static Optional<DriftClass> classify(MoneyStatus ours, ProviderAccount account) {
        return DriftClassifier.classify(Optional.ofNullable(ours), account);
    }

    @Test
    void paidAtTheProviderWhileOursIsUnresolvedIsAMissedConfirmation() {
        assertThat(classify(MoneyStatus.PENDING, derived(MoneyStatus.SUCCEEDED)))
                .contains(DriftClass.MISSED_CONFIRMATION);
        assertThat(classify(MoneyStatus.FAILED, derived(MoneyStatus.SUCCEEDED)))
                .contains(DriftClass.MISSED_CONFIRMATION);
        assertThat(classify(MoneyStatus.EXPIRED, derived(MoneyStatus.SUCCEEDED)))
                .contains(DriftClass.MISSED_CONFIRMATION);
        assertThat(classify(null, derived(MoneyStatus.SUCCEEDED))).contains(DriftClass.MISSED_CONFIRMATION);
    }

    @Test
    void failedAtTheProviderWhileOursIsSucceededIsAFalsePositive() {
        assertThat(classify(MoneyStatus.SUCCEEDED, derived(MoneyStatus.FAILED))).contains(DriftClass.FALSE_POSITIVE);
    }

    @Test
    void aWrongAmountIsAnAmountMismatch() {
        assertThat(classify(MoneyStatus.PENDING, refused("amount_mismatch", MoneyStatus.SUCCEEDED,
                PROVIDER_TRANSACTION))).contains(DriftClass.AMOUNT_MISMATCH);
    }

    @Test
    void anOrderTheProviderNeverSawIsNeverArrived() {
        assertThat(classify(MoneyStatus.PENDING, refused("unmapped_code", null, null)))
                .contains(DriftClass.NEVER_ARRIVED);
        assertThat(classify(MoneyStatus.EXPIRED, refused("no_result_code", null, null)))
                .contains(DriftClass.NEVER_ARRIVED);
    }

    @Test
    void anUnmappedCodeOnAnOrderTheProviderKnowsIsNoClass() {
        assertThat(classify(MoneyStatus.PENDING, refused("unmapped_code", null, PROVIDER_TRANSACTION))).isEmpty();
    }

    @Test
    void aSuccessCodeWithoutAnAmountIsAMissedConfirmation() {
        assertThat(classify(MoneyStatus.PENDING, refused("amount_unstated", MoneyStatus.SUCCEEDED,
                PROVIDER_TRANSACTION))).contains(DriftClass.MISSED_CONFIRMATION);
    }

    @Test
    void agreementIsNoClass() {
        assertThat(classify(MoneyStatus.PENDING, derived(MoneyStatus.FAILED))).isEmpty();
        assertThat(classify(MoneyStatus.SUCCEEDED, derived(MoneyStatus.SUCCEEDED))).isEmpty();
        assertThat(classify(MoneyStatus.PENDING, new ProviderAccount(Optional.empty(), Optional.empty(),
                Optional.empty(), PROVIDER_TRANSACTION))).isEmpty();
    }

    @Test
    void onlyADisagreementTheRulesLeftUncorrectedNeedsAPerson() {
        assertThat(DriftClassifier.needsPerson(DriftClass.MISSED_CONFIRMATION, true)).isFalse();
        assertThat(DriftClassifier.needsPerson(DriftClass.MISSED_CONFIRMATION, false)).isTrue();
        assertThat(DriftClassifier.needsPerson(DriftClass.NEVER_ARRIVED, false)).isFalse();
        assertThat(DriftClassifier.needsPerson(DriftClass.FALSE_POSITIVE, false)).isTrue();
    }
}
