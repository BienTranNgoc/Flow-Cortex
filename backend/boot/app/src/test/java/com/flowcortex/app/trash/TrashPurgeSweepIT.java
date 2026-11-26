package com.flowcortex.app.trash;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.trash.api.PurgeOutcome;
import com.flowcortex.trash.application.TrashItemActions;
import com.flowcortex.trash.application.TrashItemActions.Status;
import com.flowcortex.trash.application.TrashProperties;
import com.flowcortex.trash.application.TrashProviders;
import com.flowcortex.trash.application.TrashPurgeSweep;
import com.flowcortex.trash.application.TrashPurgeSweep.Limits;
import com.flowcortex.trash.infrastructure.TrashEntryStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The expiry sweep against a real Postgres with the two scripted providers, called through {@code run()} because the
 * cluster runner is off in the suite. The clock is fixed, retention is the 30 days of {@code application.yml}, and
 * every test starts from an empty entry table.
 */
@Testcontainers
@SpringBootTest(properties = "flowcortex.trash.enabled=true")
@Import(TrashTestProviders.class)
class TrashPurgeSweepIT extends AbstractPostgresBootTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TrashPurgeSweep sweep;

    @Autowired
    private TrashItemActions actions;

    @Autowired
    private TrashEntryStore store;

    @Autowired
    private TrashProviders providers;

    @Autowired
    private TrashProperties properties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TrashTestProviders.SettableClock clock;

    @Autowired
    private TrashTestProviders.CallLog callLog;

    @Autowired
    private TrashTestProviders.ScriptedProvider demoProvider;

    @Autowired
    private TrashTestProviders.ScriptedProvider demoLateProvider;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger sweepLog = (Logger) LoggerFactory.getLogger(TrashPurgeSweep.class);
    private final Logger actionsLog = (Logger) LoggerFactory.getLogger(TrashItemActions.class);
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clean();
        clock.set(NOW);
        logs.start();
        sweepLog.addAppender(logs);
        actionsLog.addAppender(logs);
    }

    @AfterEach
    void clean() {
        sweepLog.detachAppender(logs);
        actionsLog.detachAppender(logs);
        logs.stop();
        logs.list.clear();
        jdbc.update("DELETE FROM trash_entry");
        demoProvider.reset();
        demoLateProvider.reset();
        callLog.clear();
    }

    @Test
    void purgesExpiredAndKeepsUnexpired() {
        expired(demoLateProvider, "old", RETENTION.plusMinutes(1));
        expired(demoLateProvider, "fresh", RETENTION.minusHours(1));

        sweep.run();

        assertThat(demoLateProvider.calls()).containsExactly("purge:old");
        assertThat(demoLateProvider.hasRow("old")).isFalse();
        assertThat(entryExists("demo_late", "old")).isFalse();
        assertThat(demoLateProvider.hasRow("fresh")).isTrue();
        assertThat(entryExists("demo_late", "fresh")).isTrue();
        assertThat(summary()).contains("purged=1 ");
    }

    @Test
    void ranksBeforeAgeAcrossOwners() {
        UUID other = UUID.randomUUID();
        add(demoLateProvider, "z", owner, Duration.ofDays(60), null);
        add(demoProvider, "m", owner, Duration.ofDays(35), NOW.minus(Duration.ofDays(2)));
        add(demoProvider, "n", other, Duration.ofDays(40), NOW.minus(Duration.ofDays(2)));

        sweep.run();

        assertThat(callLog.entries()).containsExactly("demo:purge:n", "demo:purge:m", "demo_late:purge:z");
    }

    @Test
    void providerErrorKeepsTheEntryAndOthersProceed() {
        expired(demoLateProvider, "a", Duration.ofDays(33));
        expired(demoLateProvider, "b", Duration.ofDays(32));
        expired(demoLateProvider, "c", Duration.ofDays(31));
        demoLateProvider.failPurge("b", new IllegalStateException("boom"));
        UUID failingEntry = entryId("demo_late", "b");

        sweep.run();

        assertThat(demoLateProvider.calls()).containsExactly("purge:a", "purge:b", "purge:c");
        assertThat(entryExists("demo_late", "a")).isFalse();
        assertThat(entryExists("demo_late", "b")).isTrue();
        assertThat(entryExists("demo_late", "c")).isFalse();
        assertThat(demoLateProvider.hasRow("b")).isTrue();
        assertThat(messages()).filteredOn(message -> message.startsWith("trash.sweep failed"))
                .singleElement().asString().contains("type=demo_late").contains("entryId=" + failingEntry);
        assertThat(summary()).contains("purged=2 blocked=0 failed=1 ");
    }

    @Test
    void blockedStaysForTheNextPass() {
        for (String id : List.of("a", "b", "c", "d")) {
            expired(demoLateProvider, id, Duration.ofDays(31));
        }
        demoLateProvider.answerPurge("b", new PurgeOutcome.Blocked(TrashTestProviders.IN_USE));
        TrashPurgeSweep small = new TrashPurgeSweep(store, providers, actions, properties, clock, new Limits(2, 50,
                Duration.ofMinutes(20)));

        small.run();

        assertThat(demoLateProvider.calls().stream().filter("purge:b"::equals).count())
                .as("a blocked entry is asked about once per pass").isEqualTo(1);
        assertThat(entryExists("demo_late", "b")).isTrue();
        assertThat(entryExists("demo_late", "a")).isFalse();
        assertThat(entryExists("demo_late", "c")).isFalse();
        assertThat(entryExists("demo_late", "d")).isFalse();
        assertThat(summary()).contains("purged=3 blocked=1 failed=0 ");

        small.run();

        assertThat(demoLateProvider.calls().stream().filter("purge:b"::equals).count())
                .as("the next pass asks again").isEqualTo(2);
        assertThat(entryExists("demo_late", "b")).isTrue();
    }

    @Test
    void goneEntriesAreRemoved() {
        add(demoLateProvider, "g", owner, Duration.ofDays(31), null);
        demoLateProvider.reset();

        sweep.run();

        assertThat(demoLateProvider.calls()).containsExactly("purge:g");
        assertThat(entryExists("demo_late", "g")).isFalse();
    }

    @Test
    void backlogBeyondTheCapIsTakenNextPass() {
        for (int i = 1; i <= 7; i++) {
            expired(demoLateProvider, "i" + i, Duration.ofDays(40 - i));
        }
        TrashPurgeSweep capped = new TrashPurgeSweep(store, providers, actions, properties, clock, new Limits(2, 3,
                Duration.ofMinutes(20)));

        capped.run();

        assertThat(entryCount()).isEqualTo(1);
        assertThat(entryExists("demo_late", "i7")).isTrue();
        assertThat(summary()).contains("purged=6 ").endsWith("ceilingHit=true");

        logs.list.clear();
        capped.run();

        assertThat(entryCount()).isZero();
        assertThat(summary()).contains("purged=1 ").endsWith("ceilingHit=false");
    }

    @Test
    void entriesWithoutProviderAreLeftAndCounted() {
        insert("retired", "r1", owner, Duration.ofDays(45), null);
        expired(demoLateProvider, "a", Duration.ofDays(31));

        sweep.run();

        assertThat(entryExists("retired", "r1")).isTrue();
        assertThat(entryExists("demo_late", "a")).isFalse();
        assertThat(summary()).contains("withoutProvider=1 ");
    }

    @Test
    void secondPassOverDrainedEntriesDoesNothing() {
        expired(demoLateProvider, "a", Duration.ofDays(31));
        expired(demoLateProvider, "b", Duration.ofDays(31));
        sweep.run();
        assertThat(demoLateProvider.calls()).hasSize(2);
        logs.list.clear();

        sweep.run();

        assertThat(demoLateProvider.calls()).hasSize(2);
        assertThat(demoProvider.calls()).isEmpty();
        assertThat(summary()).contains("purged=0 blocked=0 failed=0 ");
    }

    @Test
    void restoreHoldingTheLockIsSkippedBySweep() throws Exception {
        expired(demoLateProvider, "8", Duration.ofDays(31));
        expired(demoLateProvider, "9", Duration.ofDays(32));
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM trash_entry WHERE item_id = '8' FOR UPDATE");
                try {
                    other.submit(sweep::run).get(30, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException("the pass did not finish while the entry was locked", failure);
                }
                assertThat(demoLateProvider.calls()).containsExactly("purge:9");
            });
        } finally {
            other.shutdownNow();
        }

        assertThat(entryExists("demo_late", "9")).isFalse();
        assertThat(entryExists("demo_late", "8")).isTrue();
        assertThat(demoLateProvider.hasRow("8")).isTrue();
    }

    @Test
    void unremindedReminderTypeIsKeptAndCounted() {
        add(demoProvider, "u", owner, Duration.ofDays(40), null);
        expired(demoLateProvider, "l", Duration.ofDays(40));

        sweep.run();

        assertThat(demoProvider.calls()).isEmpty();
        assertThat(entryExists("demo", "u")).isTrue();
        assertThat(demoLateProvider.calls()).containsExactly("purge:l");
        assertThat(summary()).contains("awaitingReminder=1 ");
    }

    @Test
    void remindedLessThan24HoursAgoIsKept() {
        add(demoProvider, "k1", owner, Duration.ofDays(40), NOW.minus(Duration.ofHours(23)).minus(Duration.ofMinutes(59)));
        add(demoProvider, "k2", owner, Duration.ofDays(40), NOW.minus(Duration.ofHours(24)));

        sweep.run();

        assertThat(demoProvider.calls()).containsExactly("purge:k2");
        assertThat(entryExists("demo", "k1")).isTrue();
        assertThat(entryExists("demo", "k2")).isFalse();
    }

    @Test
    void timeBudgetStopsThePass() {
        for (String id : List.of("a", "b", "c")) {
            expired(demoLateProvider, id, Duration.ofDays(31));
        }
        TrashPurgeSweep spent = new TrashPurgeSweep(store, providers, actions, properties, clock, new Limits(200, 50,
                Duration.ZERO));

        spent.run();

        assertThat(demoLateProvider.calls()).hasSize(1);
        assertThat(entryCount()).isEqualTo(2);
        assertThat(summary()).contains("purged=1 ").endsWith("ceilingHit=true");
    }

    @Test
    void anEntryRefreshedAfterTheSelectIsNotPurged() {
        expired(demoLateProvider, "r", RETENTION.minusHours(1));
        Instant cutoff = NOW.minus(RETENTION);

        TrashItemActions.Result result = actions.purgeExpired(demoLateProvider, entryId("demo_late", "r"), cutoff,
                Optional.empty());

        assertThat(result.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(demoLateProvider.calls()).isEmpty();
        assertThat(entryExists("demo_late", "r")).isTrue();
    }

    @Test
    void leaseIsThirtyMinutes() {
        assertThat(sweep.lease()).isEqualTo(Duration.ofMinutes(30));
        assertThat(sweep.name()).isEqualTo("trash.purge-sweep");
        assertThat(sweep.interval()).isEqualTo(Duration.ofHours(1));
    }

    private void expired(TrashTestProviders.ScriptedProvider provider, String itemId, Duration age) {
        add(provider, itemId, owner, age, null);
    }

    private void add(TrashTestProviders.ScriptedProvider provider, String itemId, UUID rowOwner, Duration age,
            Instant remindedAt) {
        provider.trash(itemId, rowOwner);
        insert(provider.type(), itemId, rowOwner, age, remindedAt);
    }

    private void insert(String type, String itemId, UUID rowOwner, Duration age, Instant remindedAt) {
        jdbc.update("INSERT INTO trash_entry (id, item_type, item_id, owner_id, deleted_by, name, name_key, "
                + "location_code, deleted_at, reminded_at) VALUES (?, ?, ?, ?, ?, 'Item', 'item', ?, ?, ?)",
                UUID.randomUUID(), type, itemId, rowOwner, rowOwner, TrashTestProviders.LOCATION,
                Timestamp.from(NOW.minus(age)), remindedAt == null ? null : Timestamp.from(remindedAt));
    }

    private boolean entryExists(String type, String itemId) {
        return jdbc.queryForObject("SELECT count(*) FROM trash_entry WHERE item_type = ? AND item_id = ?",
                Integer.class, type, itemId) == 1;
    }

    private UUID entryId(String type, String itemId) {
        return jdbc.queryForObject("SELECT id FROM trash_entry WHERE item_type = ? AND item_id = ?", UUID.class,
                type, itemId);
    }

    private int entryCount() {
        return jdbc.queryForObject("SELECT count(*) FROM trash_entry", Integer.class);
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** The one summary line of the last pass. */
    private String summary() {
        return messages().stream().filter(message -> message.startsWith("trash.sweep purged="))
                .reduce((first, second) -> second).orElseThrow(() -> new AssertionError("no pass summary logged"));
    }
}
