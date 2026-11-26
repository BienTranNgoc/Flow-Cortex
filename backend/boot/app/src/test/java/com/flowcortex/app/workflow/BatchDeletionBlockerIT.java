package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.AccountDeletionBlocker;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The open-batch blocker reads the member's own {@code running} and {@code paused} batches; every row rolls back. */
@Testcontainers
@SpringBootTest
@Transactional
class BatchDeletionBlockerIT extends AbstractPostgresBootTest {

    private static final String KEY = "workflow.open_batch";
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @Autowired
    private List<AccountDeletionBlocker> blockers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void runningAndPausedBatchesBlock() {
        for (String status : List.of("running", "paused")) {
            UUID owner = UUID.randomUUID();
            batch(owner, status);

            assertThat(blocker().blocks(owner)).as(status).isTrue();
        }
    }

    @Test
    void completedCompletedWithErrorsAndCancelledBatchesDoNotBlock() {
        UUID owner = UUID.randomUUID();
        for (String status : List.of("completed", "completed_with_errors", "cancelled")) {
            batch(owner, status);
        }

        assertThat(blocker().blocks(owner)).isFalse();
    }

    @Test
    void anotherAccountsBatchDoesNotBlock() {
        batch(UUID.randomUUID(), "running");
        batch(UUID.randomUUID(), "paused");

        assertThat(blocker().blocks(UUID.randomUUID())).isFalse();
    }

    @Test
    void keyIsWellFormedAndOrderIsTwo() {
        AccountDeletionBlocker blocker = blocker();

        assertThat(blocker.key()).matches("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$");
        assertThat(blocker.defaultSortOrder()).isEqualTo(2);
        assertThat(blocker.defaultMessageVi()).isNotBlank();
        assertThat(blocker.defaultMessageEn()).isNotBlank();
    }

    private AccountDeletionBlocker blocker() {
        return blockers.stream().filter(candidate -> candidate.key().equals(KEY)).findFirst()
                .orElseThrow(() -> new AssertionError("no blocker keyed " + KEY));
    }

    private void batch(UUID owner, String status) {
        UUID workflow = UUID.randomUUID();
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at) "
                + "VALUES (?, ?, 'Blocker fixture', false, ?, ?)", workflow, owner, now, now);
        boolean open = List.of("running", "paused").contains(status);
        jdbc.update("INSERT INTO batch_run (id, owner_id, workflow_id, concurrency, acknowledge_cost, status, "
                + "total_rows, created_at, finished_at) VALUES (?, ?, ?, 1, false, ?, 1, ?, ?)",
                UUID.randomUUID(), owner, workflow, status, now, open ? null : now);
    }
}
