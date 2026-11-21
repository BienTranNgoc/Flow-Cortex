package com.flowcortex.support.request;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.support.config.SupportLifecycleSettings;
import com.flowcortex.support.request.RequestRepository.ClaimedReplyMail;
import com.flowcortex.support.request.RequestRepository.DueReplyMail;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns the due marker an admin reply leaves on its request into one {@link SupportReplyMailRequested}, once the
 * marker's time has passed. Each request is claimed and published in its own transaction, so the publication is
 * recorded exactly when the marker is cleared; a reply that moved the marker after it was read wins the claim, and
 * nothing is mailed for the older time. Nothing is read while the reply mail is switched off, so due markers wait.
 * Runs on one instance at a time through the cluster runner.
 */
@Component
public class ReplyMailSweep implements ClusterJob {

    static final int BATCH = 200;

    private static final Logger LOG = LoggerFactory.getLogger(ReplyMailSweep.class);

    private enum Outcome { QUEUED, MESSAGE_GONE, LOST }

    private final RequestRepository requests;
    private final Settings settings;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration interval;

    ReplyMailSweep(RequestRepository requests, Settings settings, ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${flowcortex.support.reply-mail.sweep-interval:PT1M}") Duration interval) {
        this.requests = requests;
        this.settings = settings;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "support.reply-mail-sweep";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void run() {
        if (!settings.get(SupportLifecycleSettings.REPLY_MAIL_ENABLED).effective()) {
            LOG.info("support.reply.mail.sweep queued=0 skipped=disabled");
            return;
        }
        int queued = 0;
        int gone = 0;
        for (DueReplyMail row : requests.dueReplyMails(Instant.now(clock), BATCH)) {
            Outcome outcome = transaction.execute(status -> claimAndPublish(row));
            if (outcome == Outcome.QUEUED) {
                queued++;
            } else if (outcome == Outcome.MESSAGE_GONE) {
                gone++;
            }
        }
        LOG.info("support.reply.mail.sweep queued={} message_gone={}", queued, gone);
    }

    private Outcome claimAndPublish(DueReplyMail row) {
        Optional<ClaimedReplyMail> claimed = requests.claimReplyMail(row.id(), row.dueAt());
        if (claimed.isEmpty()) {
            return Outcome.LOST;
        }
        ClaimedReplyMail mail = claimed.get();
        if (requests.findRequestIdOfMessage(mail.messageId()).isEmpty()) {
            LOG.warn("support.reply.mail.sweep outcome=message_gone request={}", mail.number());
            return Outcome.MESSAGE_GONE;
        }
        events.publishEvent(new SupportReplyMailRequested(mail.requestId(), mail.messageId(), mail.requesterId(),
                mail.number(), mail.locale()));
        return Outcome.QUEUED;
    }
}
