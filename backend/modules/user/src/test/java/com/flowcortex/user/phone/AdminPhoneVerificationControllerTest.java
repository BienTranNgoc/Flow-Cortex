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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

class AdminPhoneVerificationControllerTest {

    private static final PhoneSendLimits LIMITS =
            new PhoneSendLimits(List.of("84"), 5, 3, new IpLimit(10, Duration.ofMinutes(1)), 3, 500);

    @Test
    void noWiredSenderReadsNoneWhateverIsConfigured() {
        assertThat(senderReadBy(null, "outbox")).isEqualTo(AdminPhoneVerificationController.NO_SENDER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"outbox", "vietguys"})
    void aKnownSenderIsReportedAsItself(String name) {
        assertThat(senderReadBy(mock(SmsSender.class), name)).isEqualTo(name);
    }

    @Test
    void aSenderWhoseConfiguredNameIsNotKnownReadsCustom() {
        assertThat(senderReadBy(mock(SmsSender.class), "acme"))
                .isEqualTo(AdminPhoneVerificationController.CUSTOM_SENDER);
        assertThat(senderReadBy(mock(SmsSender.class), "")).isEqualTo(AdminPhoneVerificationController.CUSTOM_SENDER);
    }

    @Test
    void noCarrierCredentialBeanReadsNone() {
        assertThat(statusOf(mock(SmsSender.class), "outbox", null).carrierCredential()).isEqualTo("none");
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "reseed_required", "unseeded"})
    void theCarrierCredentialStateIsReportedAsItIs(String state) {
        assertThat(statusOf(mock(SmsSender.class), "vietguys", () -> state).carrierCredential()).isEqualTo(state);
    }

    private static String senderReadBy(SmsSender wired, String configuredName) {
        return statusOf(wired, configuredName, null).smsSender();
    }

    private static AdminPhoneVerificationController.PhoneVerificationStatusResponse statusOf(
            SmsSender wired, String configuredName, CarrierCredentialStatus credential) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SmsSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(wired);
        @SuppressWarnings("unchecked")
        ObjectProvider<CarrierCredentialStatus> credentials = mock(ObjectProvider.class);
        when(credentials.getIfAvailable()).thenReturn(credential);
        PhoneNumberHasher hasher = mock(PhoneNumberHasher.class);
        PhoneSendAttemptStore attempts = mock(PhoneSendAttemptStore.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);
        return new AdminPhoneVerificationController(
                        true, true, configuredName, provider, credentials, hasher, LIMITS, attempts, clock)
                .status();
    }
}
