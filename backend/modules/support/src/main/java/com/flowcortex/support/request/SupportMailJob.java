package com.flowcortex.support.request;

import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.support.request.MailOutboxRepository.OutboxRow;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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

    private static final Logger LOG = LoggerFactory.getLogger(SupportMailJob.class);
    private static final int BATCH = 50;

    private final MailOutboxRepository outbox;
    private final UserDirectory users;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final SupportProperties properties;
    private final String publicUrl;
    private final Clock clock;

    SupportMailJob(MailOutboxRepository outbox, UserDirectory users, ObjectProvider<JavaMailSender> mailSender,
            SupportProperties properties, @Value("${flowcortex.app.public-url:}") String publicUrl, Clock clock) {
        if (properties.enabled() && (publicUrl == null || publicUrl.isBlank())) {
            throw new IllegalStateException("flowcortex.support.enabled is true but flowcortex.app.public-url "
                    + "(FLOWCORTEX_APP_PUBLIC_URL), the member app origin the reply mail links to, is not set");
        }
        this.outbox = outbox;
        this.users = users;
        this.mailSender = mailSender;
        this.properties = properties;
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
            JavaMailSender sender = mailSender.getIfAvailable();
            if (sender == null) {
                throw new IllegalStateException("No mail sender is configured");
            }
            sender.send(message(email, row.requestNumber()));
            outbox.markSent(row.id(), attempt, Instant.now(clock));
            LOG.info("support.reply.mail outcome=sent attempt={} request={}", attempt, row.requestNumber());
        } catch (RuntimeException e) {
            Instant failedAt = Instant.now(clock);
            // Only the class name: a send exception can carry the message and its recipient.
            String error = e.getClass().getSimpleName();
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
    }

    private SimpleMailMessage message(String email, long number) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mail().from());
        message.setTo(email);
        message.setSubject("Nodi: yêu cầu hỗ trợ #" + number + " có phản hồi mới");
        message.setText("Xin chào,\n\n"
                + "Bộ phận hỗ trợ Nodi vừa trả lời yêu cầu #" + number + " của bạn. Mở liên kết sau để xem:\n\n"
                + publicUrl + "/?help=request-" + number + "\n\n"
                + "Email này được gửi tự động, vui lòng không trả lời.\n");
        return message;
    }
}
