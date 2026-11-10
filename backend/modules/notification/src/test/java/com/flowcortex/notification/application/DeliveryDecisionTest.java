package com.flowcortex.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.notification.application.DeliveryDecision.Outcome;
import org.junit.jupiter.api.Test;

class DeliveryDecisionTest {

    @Test
    void aGoneRecipientIsDroppedWhateverThePreference() {
        assertThat(DeliveryDecision.decide(false, true)).isEqualTo(Outcome.RECIPIENT_GONE);
        assertThat(DeliveryDecision.decide(false, false)).isEqualTo(Outcome.RECIPIENT_GONE);
    }

    @Test
    void inAppOffSuppresses() {
        assertThat(DeliveryDecision.decide(true, false)).isEqualTo(Outcome.SUPPRESSED);
    }

    @Test
    void otherwiseTheRequestIsStored() {
        assertThat(DeliveryDecision.decide(true, true)).isEqualTo(Outcome.STORE);
    }
}
