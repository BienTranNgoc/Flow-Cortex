package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.support.request.MailOutboxRepository.OutboxRow;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SupportMailJobTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

    private final MailOutboxRepository outbox = mock(MailOutboxRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final Mailer mailer = mock(Mailer.class);
    private final SupportProperties properties = new SupportProperties(true, null,
            new SupportProperties.Assistant(false, "m", 2000, 50, 10, 100));
    private final SupportMailJob job = new SupportMailJob(outbox, users, mailer, properties,
            "http://localhost:5173/", Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID row = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();

    @Test
    void aDueRowIsClaimedSentWithTheLinkAndMarkedSent(CapturedOutput output) {
        due(0);
        when(mailer.send(any(OutgoingMail.class))).thenReturn(MailOutcome.SENT);

        job.sendDue();

        OutgoingMail mail = sent();
        assertThat(mail.key()).isEqualTo(MailKey.SUPPORT_REQUEST_REPLIED);
        assertThat(mail.memberId()).isEqualTo(member);
        assertThat(mail.to()).isEqualTo("m@example.com");
        assertThat(mail.locale()).isEqualTo(Locale.of("vi"));
        assertThat(mail.values()).containsExactly(12L, "http://localhost:5173/?help=request-12");
        verify(outbox).markSent(row, 1, NOW);
        assertThat(output.getOut()).contains("support.reply.mail outcome=sent attempt=1 request=12")
                .doesNotContain("m@example.com");
    }

    @Test
    void aRequestFiledInEnglishIsMailedInEnglish() {
        due("en", 0);
        when(mailer.send(any(OutgoingMail.class))).thenReturn(MailOutcome.SENT);

        job.sendDue();

        assertThat(sent().locale()).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void aFailedSendIsRetriedAfterTheNextDelayAndTheSixthGivesUp(CapturedOutput output) {
        due(2);
        when(mailer.send(any(OutgoingMail.class))).thenReturn(MailOutcome.FAILED);

        job.sendDue();
        due(5);
        job.sendDue();

        verify(outbox).markFailed(row, 3, NOW.plus(Duration.ofMinutes(15)), "send_failed", NOW);
        verify(outbox).markFailed(row, 6, NOW, "send_failed", NOW);
        verify(outbox, never()).markSent(any(), anyInt(), any());
        assertThat(output.getOut()).contains("outcome=failed attempt=3 request=12 error=send_failed")
                .contains("outcome=gave_up attempt=6 request=12").doesNotContain("m@example.com");
    }

    @Test
    void aSuppressedSendClosesTheRowWithoutRetry(CapturedOutput output) {
        due(0);
        when(mailer.send(any(OutgoingMail.class))).thenReturn(MailOutcome.SUPPRESSED);

        job.sendDue();

        verify(outbox).markFailed(row, MailOutboxRepository.MAX_ATTEMPTS, NOW, "suppressed", NOW);
        verify(outbox, never()).markSent(any(), anyInt(), any());
        assertThat(output.getOut()).contains("support.reply.mail outcome=suppressed request=12")
                .doesNotContain("m@example.com");
    }

    @Test
    void aDeletedRecipientClosesTheRowWithoutMail() {
        when(outbox.due(NOW, 50)).thenReturn(List.of(row));
        when(outbox.claim(eq(row), eq(NOW), any())).thenReturn(Optional.of(new OutboxRow(row, member, 12, "vi", 0)));
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of());

        job.sendDue();

        verify(outbox).markFailed(row, MailOutboxRepository.MAX_ATTEMPTS, NOW, "recipient deleted", NOW);
        verify(mailer, never()).send(any());
    }

    @Test
    void startupFailsWithTheHelpCenterOnAndNoPublicUrlForTheLink() {
        SupportProperties off = new SupportProperties(false, null,
                new SupportProperties.Assistant(false, "m", 2000, 50, 10, 100));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() -> new SupportMailJob(outbox, users, mailer, properties, " ", clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.app.public-url");
        assertThatCode(() -> new SupportMailJob(outbox, users, mailer, off, "", clock))
                .doesNotThrowAnyException();
    }

    @Test
    void aRowAnotherTickClaimedIsLeftAlone() {
        when(outbox.due(NOW, 50)).thenReturn(List.of(row));
        when(outbox.claim(row, NOW, NOW.plus(SupportMailJob.CLAIM_LEASE))).thenReturn(Optional.empty());

        job.sendDue();

        verify(users, never()).findEmailsByIds(any());
    }

    private OutgoingMail sent() {
        ArgumentCaptor<OutgoingMail> mail = ArgumentCaptor.forClass(OutgoingMail.class);
        verify(mailer).send(mail.capture());
        return mail.getValue();
    }

    private void due(int attempts) {
        due("vi", attempts);
    }

    private void due(String locale, int attempts) {
        when(outbox.due(NOW, 50)).thenReturn(List.of(row));
        when(outbox.claim(row, NOW, NOW.plus(SupportMailJob.CLAIM_LEASE)))
                .thenReturn(Optional.of(new OutboxRow(row, member, 12, locale, attempts)));
        when(users.findEmailsByIds(List.of(member))).thenReturn(Map.of(member, "m@example.com"));
    }
}
