package com.flowcortex.app.user;

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

/** The two blockers shipped with account deletion read the member's own unfinished runs and requests; every row rolls back. */
@Testcontainers
@SpringBootTest
@Transactional
class AccountDeletionBlockersIT extends AbstractPostgresBootTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @Autowired
    private List<AccountDeletionBlocker> blockers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void eachActiveRunStatusBlocks() {
        for (String status : List.of("running", "waiting_approval", "cancelling")) {
            UUID owner = UUID.randomUUID();
            run(owner, status);

            assertThat(blocker("workflow.active_run").blocks(owner)).as(status).isTrue();
            assertThat(blocker("support.open_request").blocks(owner)).as(status).isFalse();
        }
    }

    @Test
    void finishedRunsDoNotBlock() {
        UUID owner = UUID.randomUUID();
        for (String status : List.of("completed", "error", "cancelled")) {
            run(owner, status);
        }

        assertThat(blocker("workflow.active_run").blocks(owner)).isFalse();
    }

    @Test
    void eachOpenRequestStatusBlocks() {
        for (String status : List.of("new", "in_progress", "waiting_member")) {
            UUID requester = UUID.randomUUID();
            request(requester, status);

            assertThat(blocker("support.open_request").blocks(requester)).as(status).isTrue();
            assertThat(blocker("workflow.active_run").blocks(requester)).as(status).isFalse();
        }
    }

    @Test
    void resolvedAndClosedRequestsDoNotBlock() {
        UUID requester = UUID.randomUUID();
        request(requester, "resolved");
        request(requester, "closed");

        assertThat(blocker("support.open_request").blocks(requester)).isFalse();
    }

    @Test
    void anotherAccountsActivityDoesNotBlock() {
        run(UUID.randomUUID(), "running");
        request(UUID.randomUUID(), "new");
        UUID bystander = UUID.randomUUID();

        assertThat(blocker("workflow.active_run").blocks(bystander)).isFalse();
        assertThat(blocker("support.open_request").blocks(bystander)).isFalse();
    }

    private AccountDeletionBlocker blocker(String key) {
        return blockers.stream().filter(candidate -> candidate.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no blocker keyed " + key));
    }

    private void run(UUID owner, String status) {
        UUID workflow = UUID.randomUUID();
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at) "
                + "VALUES (?, ?, 'Blocker fixture', false, ?, ?)", workflow, owner, now, now);
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                + "VALUES (?, ?, ?, ?, 'seed', ?, ?)", UUID.randomUUID(), workflow, owner, status, now,
                List.of("running", "waiting_approval", "cancelling").contains(status) ? null : now);
    }

    private void request(UUID requester, String status) {
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("INSERT INTO support_request (id, requester_id, type, subject, status, member_unread, "
                + "last_activity_at, closed_reason, created_at, updated_at) "
                + "VALUES (?, ?, 'other', 'Blocker fixture', ?, false, ?, ?, ?, ?)", UUID.randomUUID(), requester,
                status, now, "closed".equals(status) ? "confirmed" : null, now, now);
    }
}
