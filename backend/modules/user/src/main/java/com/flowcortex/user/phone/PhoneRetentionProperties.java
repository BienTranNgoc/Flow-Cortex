package com.flowcortex.user.phone;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long send attempts and outbox messages are kept, bound from {@code
 * flowcortex.phone-verification.retention.*}. Attempts must outlive the 24-hour quota window.
 */
@Validated
@ConfigurationProperties("flowcortex.phone-verification.retention")
public record PhoneRetentionProperties(@NotNull Duration sendAttempts, @NotNull Duration smsOutbox) {

    public PhoneRetentionProperties {
        if (sendAttempts != null && sendAttempts.compareTo(PhoneSendPolicies.DAY) < 0) {
            throw new IllegalArgumentException(
                    "flowcortex.phone-verification.retention.send-attempts must be at least 24h, was " + sendAttempts);
        }
        if (smsOutbox != null && (smsOutbox.isZero() || smsOutbox.isNegative())) {
            throw new IllegalArgumentException(
                    "flowcortex.phone-verification.retention.sms-outbox must be positive, was " + smsOutbox);
        }
    }
}
