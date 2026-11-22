package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.user.phone.PhoneSendLimits.IpLimit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AdminPhoneVerificationControllerTest {

    private static final PhoneSendLimits LIMITS =
            new PhoneSendLimits(List.of("84"), 5, 3, new IpLimit(10, Duration.ofMinutes(1)), 3, 500);

    @Test
    void noWiredSenderReadsNoneWhateverIsConfigured() {
        assertThat(senderReadBy(null, "outbox")).isEqualTo(AdminPhoneVerificationController.NO_SENDER);
    }

    @Test
    void theOutboxSenderIsReportedAsItself() {
        assertThat(senderReadBy(mock(SmsSender.class), "outbox")).isEqualTo("outbox");
    }

    @Test
    void aSenderWhoseConfiguredNameIsNotKnownReadsCustom() {
        assertThat(senderReadBy(mock(SmsSender.class), "vietguys"))
                .isEqualTo(AdminPhoneVerificationController.CUSTOM_SENDER);
        assertThat(senderReadBy(mock(SmsSender.class), "")).isEqualTo(AdminPhoneVerificationController.CUSTOM_SENDER);
    }

    private static String senderReadBy(SmsSender wired, String configuredName) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SmsSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(wired);
        PhoneNumberHasher hasher = mock(PhoneNumberHasher.class);
        PhoneSendAttemptStore attempts = mock(PhoneSendAttemptStore.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);
        return new AdminPhoneVerificationController(true, true, configuredName, provider, hasher, LIMITS, attempts, clock)
                .status()
                .smsSender();
    }
}
