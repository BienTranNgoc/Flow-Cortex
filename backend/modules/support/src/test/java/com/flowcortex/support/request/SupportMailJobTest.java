package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.support.request.MailOutboxRepository.OutboxRow;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(OutputCaptureExtension.class)
class SupportMailJobTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

    private final MailOutboxRepository outbox = mock(MailOutboxRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final JavaMailSender sender = mock(JavaMailSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> senders = mock(ObjectProvider.class);
    private final SupportProperties properties = new SupportProperties(true, null,
            new SupportProperties.Assistant(false, "m", 2000, 50, 10, 100),
            new SupportProperties.Mail("no-reply@flow-cortex.dev"));
    private final SupportMailJob job = new SupportMailJob(outbox, users, senders, properties,
            "http://localhost:5173/", Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID row = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();

    @Test
    void aDueRowIsClaimedSentWithTheLinkAndMarkedSent(CapturedOutput output) {
        due(0);
        when(senders.getIfAvailable()).thenReturn(sender);

        job.sendDue();

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("m@example.com");
        assertThat(mail.getValue().getText()).contains("http://localhost:5173/?help=request-12");
        verify(outbox).markSent(row, 1, NOW);
        assertThat(output.getOut()).contains("support.reply.mail outcome=sent attempt=1 request=12")
                .doesNotContain("m@example.com");
    }

    @Test
    void aFailedSendIsRetriedAfterTheNextDelayAndTheSixthGivesUp(CapturedOutput output) {
        due(2);
        when(senders.getIfAvailable()).thenReturn(sender);
        doThrow(new MailSendException("refused for m@example.com")).when(sender).send(any(SimpleMailMessage.class));

        job.sendDue();
        due(5);
        job.sendDue();

        verify(outbox).markFailed(row, 3, NOW.plus(Duration.ofMinutes(15)), "MailSendException", NOW);
        verify(outbox).markFailed(row, 6, NOW, "MailSendException", NOW);
        assertThat(output.getOut()).contains("outcome=failed attempt=3 request=12")
                .contains("outcome=gave_up attempt=6 request=12").doesNotContain("m@example.com");
    }

    @Test
    void aDeletedRecipientClosesTheRowWithoutMail() {
        when(outbox.due(NOW, 50)).thenReturn(List.of(row));
        when(outbox.claim(eq(row), eq(NOW), any())).thenReturn(Optional.of(new OutboxRow(row, member, 12, 0)));
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of());

        job.sendDue();

        verify(outbox).markFailed(row, MailOutboxRepository.MAX_ATTEMPTS, NOW, "recipient deleted", NOW);
        verify(senders, never()).getIfAvailable();
    }

    @Test
    void withoutAMailSenderTheAttemptCountsAsFailed() {
        due(0);

        job.sendDue();

        verify(outbox).markFailed(row, 1, NOW.plus(Duration.ofMinutes(1)), "IllegalStateException", NOW);
    }

    @Test
    void startupFailsWithTheHelpCenterOnAndNoPublicUrlForTheLink() {
        SupportProperties off = new SupportProperties(false, null,
                new SupportProperties.Assistant(false, "m", 2000, 50, 10, 100),
                new SupportProperties.Mail("no-reply@flow-cortex.dev"));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() -> new SupportMailJob(outbox, users, senders, properties, " ", clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.app.public-url");
        assertThatCode(() -> new SupportMailJob(outbox, users, senders, off, "", clock)).doesNotThrowAnyException();
    }

    @Test
    void aRowAnotherTickClaimedIsLeftAlone() {
        when(outbox.due(NOW, 50)).thenReturn(List.of(row));
        when(outbox.claim(row, NOW, NOW.plus(SupportMailJob.CLAIM_LEASE))).thenReturn(Optional.empty());

        job.sendDue();

        verify(users, never()).findEmailsByIds(any());
    }

    private void due(int attempts) {
        when(outbox.due(NOW, 50)).thenReturn(List.of(row));
        when(outbox.claim(row, NOW, NOW.plus(SupportMailJob.CLAIM_LEASE)))
                .thenReturn(Optional.of(new OutboxRow(row, member, 12, attempts)));
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of(member, "m@example.com"));
    }
}
