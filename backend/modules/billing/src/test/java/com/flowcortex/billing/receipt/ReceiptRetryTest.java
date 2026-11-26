package com.flowcortex.billing.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.billing.receipt.ReceiptRetry.GiveUp;
import com.flowcortex.billing.receipt.ReceiptRetry.RetryAt;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReceiptRetryTest {

    @Test
    void theFirstFiveFailuresAreTriedAgainThirtyMinutesLater() {
        for (int attempts = 1; attempts <= 5; attempts++) {
            assertThat(ReceiptRetry.after(attempts)).as("after attempt %d", attempts)
                    .isEqualTo(new RetryAt(Duration.ofMinutes(30)));
        }
    }

    @Test
    void theSixthFailureAndAnyLaterOneGivesUp() {
        assertThat(ReceiptRetry.after(6)).isEqualTo(new GiveUp());
        assertThat(ReceiptRetry.after(7)).isEqualTo(new GiveUp());
    }

    @Test
    void noAttemptYetIsAnError() {
        assertThatThrownBy(() -> ReceiptRetry.after(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
