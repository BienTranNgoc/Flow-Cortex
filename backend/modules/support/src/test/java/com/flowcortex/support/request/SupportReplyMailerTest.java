package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.user.api.UserDirectory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(OutputCaptureExtension.class)
class SupportReplyMailerTest {

    private final UserDirectory users = mock(UserDirectory.class);
    private final JavaMailSender sender = mock(JavaMailSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> senders = mock(ObjectProvider.class);
    private final SupportProperties properties = properties(true);
    private final ResourceBundleMessageSource messages = bundle();
    private final SupportReplyMailer mailer = new SupportReplyMailer(users, senders, properties, messages,
            "http://localhost:5173/");
    private final UUID member = UUID.randomUUID();

    @Test
    void aReplyMailIsSentWithTheSubjectAndTheLinkToTheRequest(CapturedOutput output) {
        recipientExists();
        when(senders.getIfAvailable()).thenReturn(sender);

        mailer.on(event("vi"));

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("m@example.com");
        assertThat(mail.getValue().getSubject()).isEqualTo("Nodi: yêu cầu hỗ trợ #12 có phản hồi mới");
        assertThat(mail.getValue().getText()).isEqualTo("Xin chào,\n\n"
                + "Bộ phận hỗ trợ Nodi vừa trả lời yêu cầu #12 của bạn. Mở liên kết sau để xem:\n\n"
                + "http://localhost:5173/?help=request-12\n\n"
                + "Email này được gửi tự động, vui lòng không trả lời.\n");
        assertThat(output.getOut()).contains("support.reply.mail outcome=sent request=12")
                .doesNotContain("m@example.com");
    }

    @Test
    void aRequestFiledInEnglishIsMailedInEnglishAndAnyOtherLocaleInVietnamese() {
        recipientExists();
        when(senders.getIfAvailable()).thenReturn(sender);

        mailer.on(event("en"));
        mailer.on(event("fr"));

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(2)).send(mail.capture());
        assertThat(mail.getAllValues().get(0).getSubject())
                .isEqualTo("Nodi: your support request #12 has a new reply");
        assertThat(mail.getAllValues().get(0).getText()).startsWith("Hello,")
                .contains("http://localhost:5173/?help=request-12");
        assertThat(mail.getAllValues().get(1).getText()).startsWith("Xin chào,");
    }

    @Test
    void aDeletedRecipientSendsNothingReturnsNormallyAndLogsTheRequestNumber(CapturedOutput output) {
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of());

        assertThatCode(() -> mailer.on(event("vi"))).doesNotThrowAnyException();

        verify(senders, never()).getIfAvailable();
        assertThat(output.getOut()).contains("support.reply.mail outcome=recipient_deleted request=12");
    }

    @Test
    void withoutAMailSenderTheMailFailsSoTheRegistryRetriesIt(CapturedOutput output) {
        recipientExists();

        assertThatThrownBy(() -> mailer.on(event("vi"))).isInstanceOf(IllegalStateException.class);

        assertThat(output.getOut()).contains("support.reply.mail outcome=failed request=12 error=IllegalStateException");
    }

    @Test
    void aSendFailureIsRethrownAndItsLogLineHoldsTheRequestNumberAndNoAddress(CapturedOutput output) {
        recipientExists();
        when(senders.getIfAvailable()).thenReturn(sender);
        doThrow(new MailSendException("refused for m@example.com")).when(sender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> mailer.on(event("vi"))).isInstanceOf(MailSendException.class);

        assertThat(output.getOut()).contains("support.reply.mail outcome=failed request=12 error=MailSendException")
                .doesNotContain("m@example.com");
    }

    @Test
    void startupFailsWithTheHelpCenterOnAndNoPublicUrlForTheLink() {
        assertThatThrownBy(() -> new SupportReplyMailer(users, senders, properties, messages, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.app.public-url");
        assertThatCode(() -> new SupportReplyMailer(users, senders, properties(false), messages, ""))
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
                new SupportProperties.Mail("no-reply@flow-cortex.dev"));
    }

    private static ResourceBundleMessageSource bundle() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
