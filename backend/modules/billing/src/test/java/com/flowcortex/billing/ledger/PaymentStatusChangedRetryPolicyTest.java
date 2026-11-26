package com.flowcortex.billing.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.api.PaymentStatusChangedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentStatusChangedRetryPolicyTest {

    private final PaymentStatusChangedRetryPolicy policy = new PaymentStatusChangedRetryPolicy();

    @Test
    void itAppliesToThePaymentStatusEventAndGivesUpAfterEightAttempts() {
        assertThat(policy.eventType()).isEqualTo(PaymentStatusChangedEvent.class);
        assertThat(policy.maxAttempts()).isEqualTo(8);
    }

    @Test
    void theDelayDoublesFromOneMinuteAndStopsAtOneHour() {
        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.delayAfter(6)).isEqualTo(Duration.ofMinutes(32));
        assertThat(policy.delayAfter(7)).isEqualTo(Duration.ofMinutes(60));
        assertThat(policy.delayAfter(8)).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void aGiveUpIsCritical() {
        assertThat(policy.critical()).isTrue();
    }

    @Test
    void theDescriptionNamesThePaymentAndNothingPersonal() {
        UUID transactionId = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        PaymentStatusChangedEvent event = new PaymentStatusChangedEvent(transactionId, member, "topup", "pkg-50k",
                50_000L, "pending", "succeeded", "query:abc", Instant.parse("2026-11-24T10:00:00Z"));

        assertThat(policy.describe(event))
                .isEqualTo("transaction=" + transactionId + " to=succeeded evidence=query:abc")
                .doesNotContain(member.toString()).doesNotContain("50000").doesNotContain("pkg-50k");
    }
}
