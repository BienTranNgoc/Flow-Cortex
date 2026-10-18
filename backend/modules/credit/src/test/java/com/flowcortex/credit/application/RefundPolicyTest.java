package com.flowcortex.credit.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefundPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"INSUFFICIENT_CREDITS", "APPROVAL_REJECTED", "input_rejected", "probe_failed"})
    void aFailureCausedByTheMemberIsNotRefunded(String code) {
        assertThat(RefundPolicy.refunds(code)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lease_expired", "queue_timeout", "run_timeout", "contract_violation", "media_disabled",
            "worker_crashed", "approval_rejected", ""})
    void aSystemOrUnknownFailureIsRefunded(String code) {
        assertThat(RefundPolicy.refunds(code)).isTrue();
    }

    @Test
    void aFailureWithNoCodeIsRefunded() {
        assertThat(RefundPolicy.refunds(null)).isTrue();
    }

    @Test
    void theUserFaultListIsExactlyTheFourMemberCausedCodes() {
        assertThat(RefundPolicy.USER_FAULT)
                .containsExactlyInAnyOrder("INSUFFICIENT_CREDITS", "APPROVAL_REJECTED", "input_rejected", "probe_failed");
    }
}
