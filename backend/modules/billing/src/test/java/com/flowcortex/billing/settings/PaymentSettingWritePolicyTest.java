package com.flowcortex.billing.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaymentSettingWritePolicyTest {

    private static final Optional<PaymentSettingMeta> DANGEROUS =
            Optional.of(new PaymentSettingMeta("payment.test.mode", PaymentSettingGroup.REGION, true));
    private static final Optional<PaymentSettingMeta> ORDINARY =
            Optional.of(new PaymentSettingMeta("payment.test.limit", PaymentSettingGroup.REGION, false));

    @Test
    void aKeyThatIsNotServedIsUnknownWhateverTheReason() {
        assertThat(PaymentSettingWritePolicy.decide(Optional.empty(), "   "))
                .isEqualTo(new WriteVerdict.Unknown());
        assertThat(PaymentSettingWritePolicy.decide(Optional.empty(), "because"))
                .isEqualTo(new WriteVerdict.Unknown());
    }

    @Test
    void aDangerousKeyNeedsAReasonThatIsNotBlank() {
        assertThat(PaymentSettingWritePolicy.decide(DANGEROUS, "   "))
                .isEqualTo(new WriteVerdict.ReasonRequired());
    }

    @Test
    void aDangerousKeyWithNoReasonAtAllNeedsOne() {
        assertThat(PaymentSettingWritePolicy.decide(DANGEROUS, null))
                .isEqualTo(new WriteVerdict.ReasonRequired());
    }

    @Test
    void aDangerousKeyProceedsWithTheTrimmedReason() {
        assertThat(PaymentSettingWritePolicy.decide(DANGEROUS, "  contract  "))
                .isEqualTo(new WriteVerdict.Proceed("contract"));
    }

    @Test
    void anOrdinaryKeyKeepsATrimmedReasonToo() {
        assertThat(PaymentSettingWritePolicy.decide(ORDINARY, " ok "))
                .isEqualTo(new WriteVerdict.Proceed("ok"));
    }

    @Test
    void anOrdinaryKeyStoresABlankReasonAsNull() {
        assertThat(PaymentSettingWritePolicy.decide(ORDINARY, "   "))
                .isEqualTo(new WriteVerdict.Proceed(null));
        assertThat(PaymentSettingWritePolicy.decide(ORDINARY, null))
                .isEqualTo(new WriteVerdict.Proceed(null));
    }
}
