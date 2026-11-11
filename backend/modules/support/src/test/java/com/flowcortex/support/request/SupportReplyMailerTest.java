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
    private final Mailer sender = mock(Mailer.class);
    private final SupportProperties properties = properties(true);
    private final SupportReplyMailer mailer = new SupportReplyMailer(users, sender, properties,
            "http://localhost:5173/");
    private final UUID member = UUID.randomUUID();

    @Test
    void aReplyMailCarriesTheRequestNumberAndTheLinkToTheRequest(CapturedOutput output) {
        recipientExists();
        when(sender.send(any(OutgoingMail.class))).thenReturn(MailOutcome.SENT);

        mailer.on(event("vi"));

        ArgumentCaptor<OutgoingMail> mail = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().key()).isEqualTo(MailKey.SUPPORT_REQUEST_REPLIED);
        assertThat(mail.getValue().memberId()).isEqualTo(member);
        assertThat(mail.getValue().to()).isEqualTo("m@example.com");
        assertThat(mail.getValue().locale()).isEqualTo(Locale.of("vi"));
        assertThat(mail.getValue().values()).containsExactly(12L, "http://localhost:5173/?help=request-12");
        assertThat(output.getOut()).contains("support.reply.mail outcome=sent request=12")
                .doesNotContain("m@example.com");
    }

    @Test
    void aRequestFiledInEnglishIsMailedInEnglishAndAnyOtherLocaleInVietnamese() {
        recipientExists();
        when(sender.send(any(OutgoingMail.class))).thenReturn(MailOutcome.SENT);

        mailer.on(event("en"));
        mailer.on(event("fr"));

        ArgumentCaptor<OutgoingMail> mail = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(sender, times(2)).send(mail.capture());
        assertThat(mail.getAllValues().get(0).locale()).isEqualTo(Locale.ENGLISH);
        assertThat(mail.getAllValues().get(1).locale()).isEqualTo(Locale.of("vi"));
    }

    @Test
    void aDeletedRecipientSendsNothingReturnsNormallyAndLogsTheRequestNumber(CapturedOutput output) {
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of());

        assertThatCode(() -> mailer.on(event("vi"))).doesNotThrowAnyException();

        verify(sender, never()).send(any());
        assertThat(output.getOut()).contains("support.reply.mail outcome=recipient_deleted request=12");
    }

    @Test
    void aMemberWhoOptedOutIsNotRetried(CapturedOutput output) {
        recipientExists();
        when(sender.send(any(OutgoingMail.class))).thenReturn(MailOutcome.SUPPRESSED);

        assertThatCode(() -> mailer.on(event("vi"))).doesNotThrowAnyException();

        assertThat(output.getOut()).contains("support.reply.mail outcome=suppressed request=12")
                .doesNotContain("m@example.com");
    }

    @Test
    void aFailedSendIsRethrownSoTheRegistryRetriesIt(CapturedOutput output) {
        recipientExists();
        when(sender.send(any(OutgoingMail.class))).thenReturn(MailOutcome.FAILED);

        assertThatThrownBy(() -> mailer.on(event("vi"))).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("m@example.com");

        assertThat(output.getOut()).contains("support.reply.mail outcome=failed request=12 error=send_failed")
                .doesNotContain("m@example.com");
    }

    @Test
    void aSenderExceptionIsRethrownAndItsLogLineHoldsTheRequestNumberAndNoAddress(CapturedOutput output) {
        recipientExists();
        when(sender.send(any(OutgoingMail.class)))
                .thenThrow(new IllegalArgumentException("bad mail for m@example.com"));

        assertThatThrownBy(() -> mailer.on(event("vi"))).isInstanceOf(IllegalArgumentException.class);

        assertThat(output.getOut())
                .contains("support.reply.mail outcome=failed request=12 error=IllegalArgumentException")
                .doesNotContain("m@example.com");
    }

    @Test
    void startupFailsWithTheHelpCenterOnAndNoPublicUrlForTheLink() {
        assertThatThrownBy(() -> new SupportReplyMailer(users, sender, properties, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.app.public-url");
        assertThatCode(() -> new SupportReplyMailer(users, sender, properties(false), ""))
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

    private static SupportProperties properties(boolean enabled) {
        return new SupportProperties(enabled, null, new SupportProperties.Assistant(false, "m", 2000, 50, 10, 100),
                new SupportProperties.Lifecycle(true, 7, true, 0, true, 24));
    }
}
