package com.flowcortex.user.accountdeletion;

import com.flowcortex.user.accountdeletion.AccountDeletionRequest.State;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code account_deletion_request} and the {@code app_user} projection of a pending request. Every
 * write is one conditional statement, so two concurrent callers cannot both win; the unique index on
 * pending rows surfaces a lost insert race as {@code DuplicateKeyException}.
 */
@Component
class AccountDeletionRequestRepository {

    private static final String COLUMNS = "id, user_id, state, requested_at, scheduled_at, cancelled_at";

    /** The projection columns of an account; both are {@code null} while no request is pending. */
    record Projection(Instant requestedAt, Instant scheduledAt) {
    }

    private final JdbcTemplate jdbc;

    AccountDeletionRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AccountDeletionRequest> findPending(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM account_deletion_request WHERE user_id = ? "
                + "AND state = 'pending'", (row, i) -> map(row), userId).stream().findFirst();
    }

    void insertPending(UUID id, UUID userId, Instant requestedAt, Instant scheduledAt) {
        jdbc.update("INSERT INTO account_deletion_request (id, user_id, state, requested_at, scheduled_at, "
                + "cancelled_at, created_at) VALUES (?, ?, 'pending', ?, ?, NULL, ?)",
                id, userId, Timestamp.from(requestedAt), Timestamp.from(scheduledAt), Timestamp.from(requestedAt));
    }

    /**
     * Cancels the account's pending request while its deadline is still ahead; the number of rows
     * changed, {@code 0} when none was pending or the deadline has passed.
     */
    int cancelPending(UUID userId, Instant now) {
        return jdbc.update("UPDATE account_deletion_request SET state = 'cancelled', cancelled_at = ? "
                + "WHERE user_id = ? AND state = 'pending' AND scheduled_at > ?",
                Timestamp.from(now), userId, Timestamp.from(now));
    }

    /** Sets the projection only while the account has none, so a repeat request never moves the deadline. */
    int setProjection(UUID userId, Instant requestedAt, Instant scheduledAt) {
        return jdbc.update("UPDATE app_user SET deletion_requested_at = ?, deletion_scheduled_at = ? "
                + "WHERE id = ? AND deletion_requested_at IS NULL",
                Timestamp.from(requestedAt), Timestamp.from(scheduledAt), userId);
    }

    int clearProjection(UUID userId) {
        return jdbc.update("UPDATE app_user SET deletion_requested_at = NULL, deletion_scheduled_at = NULL "
                + "WHERE id = ? AND deletion_requested_at IS NOT NULL", userId);
    }

    /** The account's projection; empty when the account does not exist. */
    Optional<Projection> projection(UUID userId) {
        return jdbc.query("SELECT deletion_requested_at, deletion_scheduled_at FROM app_user WHERE id = ?",
                (row, i) -> new Projection(instant(row, "deletion_requested_at"), instant(row, "deletion_scheduled_at")),
                userId).stream().findFirst();
    }

    private static AccountDeletionRequest map(ResultSet row) throws SQLException {
        return new AccountDeletionRequest(
                row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class),
                State.of(row.getString("state")),
                instant(row, "requested_at"),
                instant(row, "scheduled_at"),
                instant(row, "cancelled_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
