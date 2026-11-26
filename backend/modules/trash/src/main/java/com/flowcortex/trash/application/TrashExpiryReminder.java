package com.flowcortex.trash.application;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.notification.api.NotificationRequested;
import com.flowcortex.trash.domain.TrashClock;
import com.flowcortex.trash.infrastructure.TrashEntryStore;
import com.flowcortex.trash.infrastructure.TrashEntryStore.ClaimedEntry;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Warns each member whose reminding entries are about to expire, once per entry. One transaction claims the due
 * entries and publishes one notification per owner, so a rollback sends nothing and a commit never loses the in-app
 * line; the mail goes out after the commit and is never retried. It returns at once when the switch is off, and with
 * the switch on it refuses to start without the app's public URL, which the mail links to.
 */
@Component
public class TrashExpiryReminder implements ClusterJob {

    private static final Logger LOG = LoggerFactory.getLogger(TrashExpiryReminder.class);
    private static final int CLAIM_LIMIT = 1000;
    /** Every member is mailed in Vietnamese: no member language is stored for this mail. */
    private static final Locale VIETNAMESE = Locale.of("vi");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    /** What one owner is told: how many entries and the earliest date any of them is deleted. */
    private record Digest(UUID ownerId, int count, Instant deletionDate) {

        Digest plus(Digest other) {
            return new Digest(ownerId, count + other.count,
                    deletionDate.isBefore(other.deletionDate) ? deletionDate : other.deletionDate);
        }
    }

    private final TrashEntryStore store;
    private final TrashProviders providers;
    private final TrashProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final UserDirectory users;
    private final Mailer mailer;
    private final String trashUrl;

    @Autowired
    TrashExpiryReminder(TrashEntryStore store, TrashProviders providers, TrashProperties properties, Clock clock,
            PlatformTransactionManager transactionManager, ApplicationEventPublisher events, UserDirectory users,
            Mailer mailer, @Value("${flowcortex.app.public-url:}") String publicUrl) {
        if (properties.enabled() && (publicUrl == null || publicUrl.isBlank())) {
            throw new IllegalStateException("flowcortex.trash.enabled is true but flowcortex.app.public-url "
                    + "(FLOWCORTEX_APP_PUBLIC_URL), the member app origin the expiry mail links to, is not set");
        }
        this.store = store;
        this.providers = providers;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.events = events;
        this.users = users;
        this.mailer = mailer;
        String origin = publicUrl == null ? "" : publicUrl;
        this.trashUrl = (origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin) + "/trash";
    }

    @Override
    public String name() {
        return "trash.expiry-reminder";
    }

    @Override
    public Duration interval() {
        return properties.reminderInterval();
    }

    @Override
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        Instant dueBy = TrashClock.reminderClaimUpTo(now, properties.retention(), properties.reminderLead());
        List<Digest> digests = transaction.execute(status -> claimAndNotify(now, dueBy));
        if (digests == null || digests.isEmpty()) {
            return;
        }
        LOG.info("trash.reminder owners={} entries={}", digests.size(), digests.stream().mapToInt(Digest::count).sum());
        mail(digests);
    }

    private List<Digest> claimAndNotify(Instant now, Instant dueBy) {
        List<ClaimedEntry> claimed = store.claimForReminder(providers.remindingTypes(), dueBy, now, CLAIM_LIMIT);
        Map<UUID, Digest> perOwner = new LinkedHashMap<>();
        for (ClaimedEntry entry : claimed) {
            Instant expiresAt = TrashClock.expiresAt(entry.deletedAt(), properties.retention());
            perOwner.merge(entry.ownerId(), new Digest(entry.ownerId(), 1, TrashClock.deletionDate(expiresAt, now)),
                    Digest::plus);
        }
        for (Digest digest : perOwner.values()) {
            events.publishEvent(new NotificationRequested(UUID.randomUUID(), digest.ownerId(), "trash_expiring",
                    "trash.notification.expiring.title", "trash.notification.expiring.body",
                    List.of(digest.count(), DATE.format(digest.deletionDate())), "/trash"));
        }
        return List.copyOf(perOwner.values());
    }

    private void mail(List<Digest> digests) {
        Map<UUID, String> emails;
        try {
            emails = users.findEmailsByIds(digests.stream().map(Digest::ownerId).toList());
        } catch (RuntimeException e) {
            LOG.error("trash.reminder.mail outcome=lookup_failed owners={} error={}", digests.size(),
                    e.getClass().getSimpleName());
            return;
        }
        for (Digest digest : digests) {
            String email = emails.get(digest.ownerId());
            if (email == null) {
                LOG.info("trash.reminder.mail outcome=no_address owner={}", digest.ownerId());
                continue;
            }
            send(digest, email);
        }
    }

    private void send(Digest digest, String email) {
        OutgoingMail mail = new OutgoingMail(MailKey.TRASH_EXPIRING, digest.ownerId(), email, VIETNAMESE,
                List.of(digest.count(), DATE.format(digest.deletionDate()), trashUrl));
        try {
            MailOutcome outcome = mailer.send(mail);
            if (outcome == MailOutcome.SENT || outcome == MailOutcome.SUPPRESSED) {
                LOG.info("trash.reminder.mail outcome={} owner={}", outcome.name().toLowerCase(Locale.ROOT),
                        digest.ownerId());
            } else {
                LOG.warn("trash.reminder.mail outcome=failed owner={}", digest.ownerId());
            }
        } catch (RuntimeException e) {
            // Only the class name: an exception can carry the message and its recipient.
            LOG.warn("trash.reminder.mail outcome=failed owner={} error={}", digest.ownerId(),
                    e.getClass().getSimpleName());
        }
    }
}
