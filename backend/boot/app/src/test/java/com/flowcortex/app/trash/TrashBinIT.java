package com.flowcortex.app.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.trash.api.TrashBin;
import com.flowcortex.trash.api.TrashRecord;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Recording and forgetting against a real Postgres: both join the caller's transaction, so what the caller commits or
 * rolls back, they commit or roll back with it. Entries are committed, so each test starts and ends with an empty table.
 */
@Testcontainers
@SpringBootTest(properties = "flowcortex.trash.enabled=true")
@Import(TrashTestProviders.class)
class TrashBinIT extends AbstractPostgresBootTest {

    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
    private static final String LOCATION = "trash.location.demo";

    @Autowired
    private TrashBin bin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TrashTestProviders.SettableClock clock;

    private TransactionTemplate transaction;
    private final UUID owner = UUID.randomUUID();
    private final UUID deleter = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        clock.set(T0);
        clean();
        jdbc.execute("CREATE TABLE trash_bin_it_owner_row (id text PRIMARY KEY)");
    }

    @AfterEach
    void clean() {
        jdbc.execute("DROP TABLE IF EXISTS trash_bin_it_owner_row");
        jdbc.update("DELETE FROM trash_entry");
    }

    @Test
    void recordInsideACommittedTransactionStoresOneEntry() {
        transaction.executeWithoutResult(status -> bin.record(record("demo", "p1", "  Bản nháp  ")));

        assertThat(entryCount()).isEqualTo(1);
        Map<String, Object> entry = entry("demo", "p1");
        assertThat(entry).containsEntry("owner_id", owner).containsEntry("deleted_by", deleter)
                .containsEntry("name", "Bản nháp").containsEntry("name_key", "ban nhap")
                .containsEntry("location_code", LOCATION).containsEntry("reminded_at", null);
        assertThat(deletedAt("demo", "p1")).isEqualTo(T0);
    }

    @Test
    void recordRolledBackLeavesNothing() {
        transaction.executeWithoutResult(status -> {
            bin.record(record("demo", "p1", "Bản nháp"));
            status.setRollbackOnly();
        });

        assertThat(entryCount()).isZero();
    }

    @Test
    void recordWithoutTransactionThrowsIllegalState() {
        assertThatThrownBy(() -> bin.record(record("demo", "p1", "Bản nháp")))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThat(entryCount()).isZero();
    }

    @Test
    void recordingAgainRefreshesDeleteTimeAndClearsReminder() {
        transaction.executeWithoutResult(status -> bin.record(record("demo", "p1", "Bản nháp")));
        jdbc.update("UPDATE trash_entry SET reminded_at = ?", Timestamp.from(T0.plus(Duration.ofDays(27))));
        Instant dayTwentyNine = T0.plus(Duration.ofDays(29));
        clock.set(dayTwentyNine);
        UUID newOwner = UUID.randomUUID();

        transaction.executeWithoutResult(status -> bin.record(new TrashRecord("demo", "p1", newOwner, deleter,
                "Bản nháp mới", "trash.location.other")));

        assertThat(entryCount()).isEqualTo(1);
        assertThat(deletedAt("demo", "p1")).isEqualTo(dayTwentyNine);
        assertThat(entry("demo", "p1")).containsEntry("reminded_at", null).containsEntry("owner_id", newOwner)
                .containsEntry("name", "Bản nháp mới").containsEntry("name_key", "ban nhap moi")
                .containsEntry("location_code", "trash.location.other");
    }

    @Test
    void blankNameIsRecordedUntitledAndTheCallerCommits() {
        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO trash_bin_it_owner_row (id) VALUES ('p1')");
            bin.record(record("demo", "p1", "   "));
            bin.record(record("demo", "p2", null));
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trash_bin_it_owner_row", Integer.class)).isEqualTo(1);
        assertThat(entry("demo", "p1")).containsEntry("name", null).containsEntry("name_key", "");
        assertThat(entry("demo", "p2")).containsEntry("name", null).containsEntry("name_key", "");
    }

    @Test
    void malformedIdOrLocationThrowsIllegalArgument() {
        TrashRecord badId = new TrashRecord("demo", "a b", owner, deleter, "Bản nháp", LOCATION);
        TrashRecord badType = new TrashRecord("Demo", "p1", owner, deleter, "Bản nháp", LOCATION);
        TrashRecord badLocation = new TrashRecord("demo", "p1", owner, deleter, "Bản nháp", "trash location");
        TrashRecord noOwner = new TrashRecord("demo", "p1", null, deleter, "Bản nháp", LOCATION);
        TrashRecord noDeleter = new TrashRecord("demo", "p1", owner, null, "Bản nháp", LOCATION);

        for (TrashRecord malformed : new TrashRecord[] {badId, badType, badLocation, noOwner, noDeleter}) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> bin.record(malformed)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(entryCount()).isZero();
    }

    @Test
    void forgetRemovesTheEntryInTheCallersTransaction() {
        transaction.executeWithoutResult(status -> {
            bin.record(record("demo", "p1", "Bản nháp"));
            bin.record(record("demo", "p2", "Bản khác"));
        });

        transaction.executeWithoutResult(status -> bin.forget("demo", "p1"));

        assertThat(entryCount()).isEqualTo(1);
        assertThat(entry("demo", "p2")).containsEntry("name", "Bản khác");
    }

    @Test
    void forgetRolledBackKeepsTheEntry() {
        transaction.executeWithoutResult(status -> bin.record(record("demo", "p1", "Bản nháp")));

        transaction.executeWithoutResult(status -> {
            bin.forget("demo", "p1");
            status.setRollbackOnly();
        });

        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void forgetWithoutTransactionThrowsIllegalState() {
        transaction.executeWithoutResult(status -> bin.record(record("demo", "p1", "Bản nháp")));

        assertThatThrownBy(() -> bin.forget("demo", "p1")).isExactlyInstanceOf(IllegalStateException.class);

        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void forgetOfAnUnknownItemIsANoOp() {
        transaction.executeWithoutResult(status -> bin.record(record("demo", "p1", "Bản nháp")));

        transaction.executeWithoutResult(status -> {
            bin.forget("demo", "never-recorded");
            bin.forget("demo_late", "p1");
        });

        assertThat(entryCount()).isEqualTo(1);
    }

    private TrashRecord record(String type, String itemId, String name) {
        return new TrashRecord(type, itemId, owner, deleter, name, LOCATION);
    }

    private int entryCount() {
        return jdbc.queryForObject("SELECT count(*) FROM trash_entry", Integer.class);
    }

    private Map<String, Object> entry(String type, String itemId) {
        return jdbc.queryForMap("SELECT * FROM trash_entry WHERE item_type = ? AND item_id = ?", type, itemId);
    }

    private Instant deletedAt(String type, String itemId) {
        return jdbc.queryForObject("SELECT deleted_at FROM trash_entry WHERE item_type = ? AND item_id = ?",
                Timestamp.class, type, itemId).toInstant();
    }
}
