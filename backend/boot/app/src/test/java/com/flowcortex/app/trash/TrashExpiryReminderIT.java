package com.flowcortex.app.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.app.workflow.AbstractWorkflowModuleIT;
import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.notification.api.NotificationRequested;
import com.flowcortex.trash.api.TrashBin;
import com.flowcortex.trash.api.TrashRecord;
import com.flowcortex.trash.application.TrashExpiryReminder;
import com.flowcortex.user.api.UserView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The expiry reminder against a real Postgres, called through {@code run()} because the cluster runner is off in the
 * suite. The clock is fixed at 09:00 in Ho Chi Minh City on 15 November, retention is the 30 days and the lead the 3
 * days of {@code application.yml}, and the mailer records instead of sending.
 */
@SpringBootTest(properties = "flowcortex.trash.enabled=true")
@Import({TrashTestProviders.class, TrashExpiryReminderIT.Recorders.class})
class TrashExpiryReminderIT extends AbstractWorkflowModuleIT {

    private static final Instant NOW = Instant.parse("2026-11-15T02:00:00Z");
    private static final Duration WAIT_FOR_DELIVERY = Duration.ofSeconds(15);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TrashExpiryReminder reminder;

    @Autowired
    private TrashBin bin;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TrashTestProviders.SettableClock clock;

    @Autowired
    private RecordingMailer mailer;

    @Autowired
    private PublishedNotifications published;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger reminderLog = (Logger) LoggerFactory.getLogger(TrashExpiryReminder.class);

    @BeforeEach
    void setUp() {
        clean();
        clock.set(NOW);
        mailer.reset();
        published.reset();
        logs.start();
        reminderLog.addAppender(logs);
    }

    @AfterEach
    void clean() {
        reminderLog.detachAppender(logs);
        logs.stop();
        logs.list.clear();
        jdbc.update("DELETE FROM trash_entry");
    }

    @Test
    void claimsOnlyRemindingEntriesInsideTheLeadWindow() {
        UUID owner = UUID.randomUUID();
        insert("demo", "inside", owner, Duration.ofDays(28));
        insert("demo", "outside", owner, Duration.ofDays(20));
        insert("demo_late", "late", owner, Duration.ofDays(29));

        reminder.run();

        assertThat(remindedAt("demo", "inside")).isEqualTo(NOW);
        assertThat(remindedAt("demo", "outside")).isNull();
        assertThat(remindedAt("demo_late", "late")).isNull();
        assertThat(published.forMember(owner)).hasSize(1);
    }

    @Test
    void claimsAPastExpiryUnremindedEntry() {
        UserView member = newUser("past-expiry");
        insert("demo", "old", member.id(), Duration.ofDays(40));

        reminder.run();

        assertThat(remindedAt("demo", "old")).isEqualTo(NOW);
        awaitNotification(member.id());
        assertThat(notificationArgs(member.id())).containsExactly("1", "16/11/2026");
        assertThat(mailer.sent(member.id()).getFirst().values()).containsExactly(1, "16/11/2026",
                "http://localhost:5173/trash");
    }

    @Test
    void reRecordedEntryIsRemindedAgain() {
        UUID owner = UUID.randomUUID();
        insert("demo", "again", owner, Duration.ofDays(28));
        reminder.run();
        assertThat(remindedAt("demo", "again")).isEqualTo(NOW);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> bin.record(new TrashRecord("demo",
                "again", owner, owner, "Item", TrashTestProviders.LOCATION)));
        assertThat(remindedAt("demo", "again")).isNull();
        clock.set(NOW.plus(Duration.ofDays(28)));
        reminder.run();

        assertThat(remindedAt("demo", "again")).isEqualTo(NOW.plus(Duration.ofDays(28)));
        assertThat(published.forMember(owner)).hasSize(2);
    }

    @Test
    void oneNotificationAndOneMailPerOwner() {
        UserView first = newUser("digest-a");
        UserView second = newUser("digest-b");
        insert("demo", "a1", first.id(), Duration.ofDays(28));
        insert("demo", "a2", first.id(), Duration.ofDays(27));
        insert("demo", "b1", second.id(), Duration.ofDays(28));

        reminder.run();

        awaitNotification(first.id());
        awaitNotification(second.id());
        assertThat(notificationCount(first.id())).isEqualTo(1);
        assertThat(notificationArgs(first.id())).containsExactly("2", "17/11/2026");
        assertThat(jdbc.queryForObject("SELECT link FROM notification WHERE user_id = ? AND type = 'trash_expiring'",
                String.class, first.id())).isEqualTo("/trash");
        assertThat(notificationArgs(second.id())).containsExactly("1", "17/11/2026");

        List<OutgoingMail> firstMails = mailer.sent(first.id());
        assertThat(firstMails).hasSize(1);
        OutgoingMail mail = firstMails.getFirst();
        assertThat(mail.key()).isEqualTo(MailKey.TRASH_EXPIRING);
        assertThat(mail.to()).isEqualTo(first.email());
        assertThat(mail.locale()).isEqualTo(Locale.of("vi"));
        assertThat(mail.values()).containsExactly(2, "17/11/2026", "http://localhost:5173/trash");
        assertThat(mailer.sent(second.id())).hasSize(1);
    }

    @Test
    void nextPassSendsNothing() {
        UserView member = newUser("next-pass");
        insert("demo", "once", member.id(), Duration.ofDays(28));
        reminder.run();
        awaitNotification(member.id());

        clock.set(NOW.plus(Duration.ofHours(1)));
        reminder.run();

        assertThat(published.forMember(member.id())).hasSize(1);
        assertThat(mailer.sent(member.id())).hasSize(1);
        assertThat(notificationCount(member.id())).isEqualTo(1);
    }

    @Test
    void concurrentPassesClaimEachEntryOnce() throws Exception {
        List<UUID> owners = new ArrayList<>();
        int entries = 0;
        for (int member = 0; member < 3; member++) {
            UUID owner = UUID.randomUUID();
            owners.add(owner);
            for (int item = 0; item < 20; item++) {
                insert("demo", "c" + member + "-" + item, owner, Duration.ofDays(28));
                entries++;
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> passes = new ArrayList<>();
            for (int pass = 0; pass < 2; pass++) {
                passes.add(pool.submit(() -> {
                    go.await();
                    reminder.run();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> pass : passes) {
                pass.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trash_entry WHERE reminded_at = ?", Integer.class,
                Timestamp.from(NOW))).isEqualTo(entries);
        int counted = owners.stream().flatMap(owner -> published.forMember(owner).stream())
                .mapToInt(request -> ((Number) request.args().getFirst()).intValue()).sum();
        assertThat(counted).as("entries named across every digest").isEqualTo(entries);
    }

    @Test
    void failedMailIsLoggedNotRetried() {
        UserView member = newUser("mail-fails");
        insert("demo", "f", member.id(), Duration.ofDays(28));
        mailer.answer(MailOutcome.FAILED);

        reminder.run();

        awaitNotification(member.id());
        assertThat(mailer.sent(member.id())).hasSize(1);
        assertThat(messages()).anyMatch(line -> line.contains("outcome=failed") && line.contains(member.id().toString()))
                .noneMatch(line -> line.contains(member.email()));

        clock.set(NOW.plus(Duration.ofHours(1)));
        reminder.run();

        assertThat(mailer.sent(member.id())).hasSize(1);
        assertThat(notificationCount(member.id())).isEqualTo(1);
    }

    @Test
    void aMailerThatThrowsDoesNotStopTheOtherOwners() {
        UserView broken = newUser("mail-throws");
        UserView healthy = newUser("mail-healthy");
        insert("demo", "t1", broken.id(), Duration.ofDays(28));
        insert("demo", "t2", healthy.id(), Duration.ofDays(28));
        mailer.throwFor(broken.id());

        reminder.run();

        assertThat(mailer.sent(healthy.id())).hasSize(1);
        assertThat(messages()).anyMatch(line -> line.contains("outcome=failed")
                && line.contains(broken.id().toString()));
        assertThat(remindedAt("demo", "t1")).isEqualTo(NOW);
    }

    @Test
    void ownerWithoutAddressGetsTheNotificationOnly() {
        UUID gone = UUID.randomUUID();
        UserView member = newUser("has-address");
        insert("demo", "g", gone, Duration.ofDays(28));
        insert("demo", "h", member.id(), Duration.ofDays(28));

        reminder.run();

        assertThat(published.forMember(gone)).hasSize(1);
        assertThat(mailer.sent(gone)).isEmpty();
        assertThat(mailer.sent(member.id())).hasSize(1);
        assertThat(messages()).anyMatch(line -> line.contains("outcome=no_address") && line.contains(gone.toString()));
    }

    @Test
    void rolledBackClaimSendsNothing() {
        UserView member = newUser("rolls-back");
        insert("demo", "r", member.id(), Duration.ofDays(28));
        published.failNextPublish();

        assertThatThrownBy(reminder::run).isInstanceOf(IllegalStateException.class);

        assertThat(remindedAt("demo", "r")).isNull();
        assertThat(mailer.sent(member.id())).isEmpty();
        assertThat(published.forMember(member.id())).isEmpty();
        assertThat(notificationCount(member.id())).isZero();

        reminder.run();

        assertThat(remindedAt("demo", "r")).isEqualTo(NOW);
        assertThat(mailer.sent(member.id())).hasSize(1);
    }

    private void insert(String type, String itemId, UUID owner, Duration age) {
        jdbc.update("INSERT INTO trash_entry (id, item_type, item_id, owner_id, deleted_by, name, name_key, "
                + "location_code, deleted_at) VALUES (?, ?, ?, ?, ?, 'Item', 'item', ?, ?)",
                UUID.randomUUID(), type, itemId, owner, owner, TrashTestProviders.LOCATION,
                Timestamp.from(NOW.minus(age)));
    }

    private Instant remindedAt(String type, String itemId) {
        Timestamp stamp = jdbc.queryForObject("SELECT reminded_at FROM trash_entry WHERE item_type = ? "
                + "AND item_id = ?", Timestamp.class, type, itemId);
        return stamp == null ? null : stamp.toInstant();
    }

    private void awaitNotification(UUID member) {
        await().atMost(WAIT_FOR_DELIVERY).until(() -> notificationCount(member) >= 1);
    }

    private long notificationCount(UUID member) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ? AND type = 'trash_expiring'",
                Long.class, member);
    }

    private List<String> notificationArgs(UUID member) {
        return jdbc.queryForObject("SELECT args->>0, args->>1 FROM notification WHERE user_id = ? "
                + "AND type = 'trash_expiring'", (rs, row) -> List.of(rs.getString(1), rs.getString(2)), member);
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Records what the reminder mails; answers {@code SENT} unless a test scripts otherwise. */
    static final class RecordingMailer implements Mailer {

        private final List<OutgoingMail> mails = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<MailOutcome> outcome = new AtomicReference<>(MailOutcome.SENT);
        private final AtomicReference<UUID> throwFor = new AtomicReference<>();

        @Override
        public MailOutcome send(OutgoingMail mail) {
            mails.add(mail);
            if (mail.memberId().equals(throwFor.get())) {
                throw new IllegalStateException("relay down for " + mail.to());
            }
            return outcome.get();
        }

        List<OutgoingMail> sent(UUID member) {
            synchronized (mails) {
                return mails.stream().filter(mail -> mail.key() == MailKey.TRASH_EXPIRING
                        && mail.memberId().equals(member)).toList();
            }
        }

        void answer(MailOutcome answer) {
            outcome.set(answer);
        }

        void throwFor(UUID member) {
            throwFor.set(member);
        }

        void reset() {
            mails.clear();
            outcome.set(MailOutcome.SENT);
            throwFor.set(null);
        }
    }

    /** Sees every {@code trash_expiring} request the moment it is published, and can refuse the next one. */
    static final class PublishedNotifications {

        private final List<NotificationRequested> requests = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean failNext = new AtomicBoolean();

        @EventListener
        void on(NotificationRequested request) {
            if (!"trash_expiring".equals(request.type())) {
                return;
            }
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("publish refused");
            }
            requests.add(request);
        }

        List<NotificationRequested> forMember(UUID member) {
            synchronized (requests) {
                return requests.stream().filter(request -> request.memberId().equals(member)).toList();
            }
        }

        void failNextPublish() {
            failNext.set(true);
        }

        void reset() {
            requests.clear();
            failNext.set(false);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recorders {

        @Bean
        @Primary
        RecordingMailer recordingMailer() {
            return new RecordingMailer();
        }

        @Bean
        PublishedNotifications publishedNotifications() {
            return new PublishedNotifications();
        }
    }
}
