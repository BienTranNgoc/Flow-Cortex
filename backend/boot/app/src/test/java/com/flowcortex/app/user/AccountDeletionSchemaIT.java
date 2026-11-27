package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The account-deletion tables refuse the rows the request rules never produce; every row rolls back. */
@Testcontainers
@SpringBootTest
@Transactional
class AccountDeletionSchemaIT extends AbstractPostgresBootTest {

    private static final Instant REQUESTED = Instant.parse("2026-11-15T10:00:00Z");
    private static final Instant SCHEDULED = REQUESTED.plus(30, ChronoUnit.DAYS);

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aSecondPendingRowIsRefused() {
        UUID user = UUID.randomUUID();
        request(user, "pending", null);

        assertThatCode(() -> request(user, "cancelled", REQUESTED.plus(1, ChronoUnit.DAYS)))
                .doesNotThrowAnyException();
        assertThatCode(() -> request(user, "cancelled", REQUESTED.plus(2, ChronoUnit.DAYS)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> request(user, "pending", null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aHalfProjectionIsRefused() {
        UUID user = appUser();

        assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET deletion_requested_at = ? WHERE id = ?",
                Timestamp.from(REQUESTED), user)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aWholeProjectionIsAccepted() {
        UUID user = appUser();

        jdbc.update("UPDATE app_user SET deletion_requested_at = ?, deletion_scheduled_at = ? WHERE id = ?",
                Timestamp.from(REQUESTED), Timestamp.from(SCHEDULED), user);

        assertThat(jdbc.queryForObject("SELECT deletion_scheduled_at FROM app_user WHERE id = ?", Timestamp.class, user))
                .isEqualTo(Timestamp.from(SCHEDULED));
    }

    @Test
    void theTwoConditionsAreSeeded() {
        // Conditions added by later modules are seeded at runtime from their defaults, not by this migration.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT key, enabled, sort_order, message_vi, message_en FROM deletion_block_condition "
                        + "WHERE key IN ('workflow.active_run', 'support.open_request') ORDER BY sort_order");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("key", "workflow.active_run").containsEntry("enabled", true)
                .containsEntry("sort_order", 0)
                .containsEntry("message_en", "A workflow run is still in progress. Wait until it finishes, or contact "
                        + "support, then try again.")
                .containsEntry("message_vi", "Bạn có workflow đang chạy. Hãy chờ chạy xong hoặc liên hệ bộ phận hỗ trợ, "
                        + "rồi thử lại.");
        assertThat(rows.get(1)).containsEntry("key", "support.open_request").containsEntry("enabled", true)
                .containsEntry("sort_order", 1)
                .containsEntry("message_en", "You have an open Help Center request. Wait until it is resolved, or "
                        + "contact support, then try again.")
                .containsEntry("message_vi", "Bạn có yêu cầu hỗ trợ đang mở. Hãy chờ yêu cầu được giải quyết hoặc liên "
                        + "hệ bộ phận hỗ trợ, rồi thử lại.");
    }

    @Test
    void aRequestRowOutlivesItsUser() {
        UUID user = appUser();
        request(user, "pending", null);

        jdbc.update("DELETE FROM app_user WHERE id = ?", user);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_deletion_request WHERE user_id = ?", Long.class,
                user)).isOne();
    }

    private UUID appUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO app_user (id, owner_id, email, password_hash, name, role, status, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'x', 'Member', 'MEMBER', 'ACTIVE', ?, ?)",
                id, id, "deletion-" + id + "@example.com", now, now);
        return id;
    }

    private void request(UUID user, String state, Instant cancelledAt) {
        jdbc.update("INSERT INTO account_deletion_request (id, user_id, state, requested_at, scheduled_at, "
                + "cancelled_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), user, state,
                Timestamp.from(REQUESTED), Timestamp.from(SCHEDULED),
                cancelledAt == null ? null : Timestamp.from(cancelledAt), Timestamp.from(REQUESTED));
    }
}
