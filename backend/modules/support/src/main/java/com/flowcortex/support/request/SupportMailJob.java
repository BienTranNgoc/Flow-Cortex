package com.flowcortex.support.request;

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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mails the member a link to each admin reply. A failed send is retried after 1, 5, 15, 60 and 240 minutes, then
 * given up; the reply itself is already saved. Logs carry the request number, never the address.
 */
@Component
public class SupportMailJob {

    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofMinutes(60), Duration.ofMinutes(240));
    /** Longer than any send takes, so the next tick never picks up a row still being sent. */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

    /** A request filed in any language other than English is mailed in Vietnamese. */
    private static final Locale VIETNAMESE = Locale.of("vi");
    private static final Logger LOG = LoggerFactory.getLogger(SupportMailJob.class);
    private static final int BATCH = 50;

    private final MailOutboxRepository outbox;
    private final UserDirectory users;
    private final Mailer mailer;
    private final String publicUrl;
    private final Clock clock;

    SupportMailJob(MailOutboxRepository outbox, UserDirectory users, Mailer mailer, SupportProperties properties,
            @Value("${flowcortex.app.public-url:}") String publicUrl, Clock clock) {
        if (properties.enabled() && (publicUrl == null || publicUrl.isBlank())) {
            throw new IllegalStateException("flowcortex.support.enabled is true but flowcortex.app.public-url "
                    + "(FLOWCORTEX_APP_PUBLIC_URL), the member app origin the reply mail links to, is not set");
        }
        this.outbox = outbox;
        this.users = users;
        this.mailer = mailer;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowcortex.support.mail.interval:PT1M}")
    public void sendDue() {
        Instant now = Instant.now(clock);
        for (UUID id : outbox.due(now, BATCH)) {
            outbox.claim(id, now, now.plus(CLAIM_LEASE)).ifPresent(this::send);
        }
    }

    private void send(OutboxRow row) {
        int attempt = row.attempts() + 1;
        String email = users.findEmailsByIds(List.of(row.recipientUserId())).get(row.recipientUserId());
        if (email == null) {
            Instant now = Instant.now(clock);
            outbox.markFailed(row.id(), MailOutboxRepository.MAX_ATTEMPTS, now, "recipient deleted", now);
            LOG.info("support.reply.mail outcome=recipient_deleted request={}", row.requestNumber());
            return;
        }
        try {
            MailOutcome outcome = mailer.send(mail(email, row));
            if (outcome == MailOutcome.SENT) {
                outbox.markSent(row.id(), attempt, Instant.now(clock));
                LOG.info("support.reply.mail outcome=sent attempt={} request={}", attempt, row.requestNumber());
            } else if (outcome == MailOutcome.SUPPRESSED) {
                Instant now = Instant.now(clock);
                outbox.markFailed(row.id(), MailOutboxRepository.MAX_ATTEMPTS, now, "suppressed", now);
                LOG.info("support.reply.mail outcome=suppressed request={}", row.requestNumber());
            } else {
                retryOrGiveUp(row, attempt, "send_failed");
            }
        } catch (RuntimeException e) {
            // Only the class name: an exception can carry the message and its recipient.
            retryOrGiveUp(row, attempt, e.getClass().getSimpleName());
        }
    }

    private void retryOrGiveUp(OutboxRow row, int attempt, String error) {
        Instant failedAt = Instant.now(clock);
        if (attempt >= MailOutboxRepository.MAX_ATTEMPTS) {
            outbox.markFailed(row.id(), attempt, failedAt, error, failedAt);
            LOG.warn("support.reply.mail outcome=gave_up attempt={} request={} error={}", attempt,
                    row.requestNumber(), error);
        } else {
            outbox.markFailed(row.id(), attempt, failedAt.plus(RETRY_DELAYS.get(attempt - 1)), error, failedAt);
            LOG.warn("support.reply.mail outcome=failed attempt={} request={} error={}", attempt,
                    row.requestNumber(), error);
        }
    }

    private OutgoingMail mail(String email, OutboxRow row) {
        Locale locale = "en".equals(row.locale()) ? Locale.ENGLISH : VIETNAMESE;
        long number = row.requestNumber();
        return new OutgoingMail(MailKey.SUPPORT_REQUEST_REPLIED, row.recipientUserId(), email, locale,
                List.of(number, publicUrl + "/?help=request-" + number));
    }
}
