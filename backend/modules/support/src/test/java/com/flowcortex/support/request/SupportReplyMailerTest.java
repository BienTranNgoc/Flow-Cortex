package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.user.api.UserDirectory;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SupportReplyMailerTest {

    private final UserDirectory users = mock(UserDirectory.class);
    private final Mailer mailer = mock(Mailer.class);
    private final SupportReplyMailer replyMailer = new SupportReplyMailer(users, mailer, properties(true),
            "http://localhost:5173/");
    private final UUID member = UUID.randomUUID();

    @Test
    void aReplyMailIsSentWithTheLinkToTheRequest(CapturedOutput output) {
        recipientExists();
        when(mailer.send(any(OutgoingMail.class))).thenReturn(MailOutcome.SENT);

        replyMailer.on(event("vi"));

        OutgoingMail mail = sent();
        assertThat(mail.key()).isEqualTo(MailKey.SUPPORT_REQUEST_REPLIED);
        assertThat(mail.memberId()).isEqualTo(member);
        assertThat(mail.to()).isEqualTo("m@example.com");
        assertThat(mail.locale()).isEqualTo(Locale.of("vi"));
        assertThat(mail.values()).containsExactly(12L, "http://localhost:5173/?help=request-12");
        assertThat(output.getOut()).contains("support.reply.mail outcome=sent request=12")
                .doesNotContain("m@example.com");
    }

    @Test
    void aRequestFiledInEnglishIsMailedInEnglishAndAnyOtherLocaleInVietnamese() {
        recipientExists();
        when(mailer.send(any(OutgoingMail.class))).thenReturn(MailOutcome.SENT);

        replyMailer.on(event("en"));
        replyMailer.on(event("fr"));

        ArgumentCaptor<OutgoingMail> mail = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(mailer, times(2)).send(mail.capture());
        assertThat(mail.getAllValues().get(0).locale()).isEqualTo(Locale.ENGLISH);
        assertThat(mail.getAllValues().get(1).locale()).isEqualTo(Locale.of("vi"));
    }

    @Test
    void aDeletedRecipientSendsNothingReturnsNormallyAndLogsTheRequestNumber(CapturedOutput output) {
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of());

        assertThatCode(() -> replyMailer.on(event("vi"))).doesNotThrowAnyException();

        verify(mailer, never()).send(any());
        assertThat(output.getOut()).contains("support.reply.mail outcome=recipient_deleted request=12");
    }

    @Test
    void aSuppressedSendReturnsNormallySoTheRegistryDoesNotRetryIt(CapturedOutput output) {
        recipientExists();
        when(mailer.send(any(OutgoingMail.class))).thenReturn(MailOutcome.SUPPRESSED);

        assertThatCode(() -> replyMailer.on(event("vi"))).doesNotThrowAnyException();

        assertThat(output.getOut()).contains("support.reply.mail outcome=suppressed request=12")
                .doesNotContain("m@example.com");
    }

    @Test
    void aFailedSendIsRethrownSoTheRegistryRetriesItAndTheLogHoldsNoAddress(CapturedOutput output) {
        recipientExists();
        when(mailer.send(any(OutgoingMail.class))).thenReturn(MailOutcome.FAILED);

        assertThatThrownBy(() -> replyMailer.on(event("vi"))).isInstanceOf(IllegalStateException.class);

        assertThat(output.getOut()).contains("support.reply.mail outcome=failed request=12 error=send_failed")
                .doesNotContain("m@example.com");
    }

    @Test
    void startupFailsWithTheHelpCenterOnAndNoPublicUrlForTheLink() {
        assertThatThrownBy(() -> new SupportReplyMailer(users, mailer, properties(true), " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.app.public-url");
        assertThatCode(() -> new SupportReplyMailer(users, mailer, properties(false), ""))
                .doesNotThrowAnyException();
    }

    @Test
    void theRetryPolicyAllowsSixAttemptsWithGrowingDelays() {
        SupportReplyMailRetryPolicy policy = new SupportReplyMailRetryPolicy();

        assertThat(policy.eventType()).isEqualTo(SupportReplyMailRequested.class);
        assertThat(policy.maxAttempts()).isEqualTo(6);
        assertThat(List.of(1, 2, 3, 4, 5).stream().map(policy::delayAfter).toList()).containsExactly(
                Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60),
                Duration.ofMinutes(240));
    }

    private SupportReplyMailRequested event(String locale) {
        return new SupportReplyMailRequested(UUID.randomUUID(), UUID.randomUUID(), member, 12, locale);
    }

    private void recipientExists() {
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of(member, "m@example.com"));
    }

    private OutgoingMail sent() {
        ArgumentCaptor<OutgoingMail> mail = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(mailer).send(mail.capture());
        return mail.getValue();
    }

    private static SupportProperties properties(boolean enabled) {
        return new SupportProperties(enabled, null, new SupportProperties.Assistant(false, 2000, 50, 10, 100),
                new SupportProperties.Lifecycle(true, 7, true, 0, true, 24));
    }
}
