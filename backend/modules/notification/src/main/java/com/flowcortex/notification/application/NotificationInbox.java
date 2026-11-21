package com.flowcortex.notification.application;

import com.flowcortex.notification.api.NotificationRequested;
import com.flowcortex.notification.application.DeliveryDecision.Outcome;
import com.flowcortex.notification.infrastructure.NotificationRepository;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers a {@link NotificationRequested} after the producer's transaction commits, in its own
 * transaction, retried by the event publication registry when it fails. Storing is idempotent by id.
 * The recipient is looked up through the email lookup, not {@code findById}: the latter throws for a
 * suspended member, who still exists and still gets the notification.
 */
@Component
class NotificationInbox {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationInbox.class);

    private final NotificationRepository repository;
    private final UserDirectory userDirectory;
    private final InboxSignals signals;
    private final NotificationProperties properties;
    private final Clock clock;

    NotificationInbox(NotificationRepository repository, UserDirectory userDirectory, InboxSignals signals,
            NotificationProperties properties, Clock clock) {
        this.repository = repository;
        this.userDirectory = userDirectory;
        this.signals = signals;
        this.properties = properties;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(NotificationRequested request) {
        deliver(request);
    }

    @Transactional
    void deliver(NotificationRequested request) {
        boolean recipientExists = userDirectory.findEmailsByIds(Set.of(request.memberId()))
                .containsKey(request.memberId());
        boolean inAppEnabled = recipientExists && repository.inAppEnabled(request.memberId());
        Outcome decision = DeliveryDecision.decide(recipientExists, inAppEnabled);
        switch (decision) {
            case STORE -> store(request);
            case SUPPRESSED -> log(request, "suppressed", 0);
            default -> log(request, "recipient_gone", 0);
        }
    }

    private void store(NotificationRequested request) {
        if (repository.insert(request, Instant.now(clock)) == 0) {
            log(request, "duplicate", 0);
            return;
        }
        int trimmed = repository.trim(request.memberId(), properties.maxPerMember());
        signals.changed(request.memberId());
        log(request, "stored", trimmed);
    }

    private static void log(NotificationRequested request, String outcome, int trimmed) {
        LOG.info("notification.deliver type={} outcome={} memberId={} trimmed={}", request.type(), outcome,
                request.memberId(), trimmed);
    }
}
