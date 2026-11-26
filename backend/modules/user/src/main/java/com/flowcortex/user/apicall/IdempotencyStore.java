package com.flowcortex.user.apicall;

import com.flowcortex.user.apicall.IdempotencyDecision.Row;
import com.flowcortex.user.apicall.IdempotencyDecision.State;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * {@code api_token_idempotency}: one row per owner and key. Every write is a single statement that
 * commits at once, and a reservation is only ever changed through its {@code reserved_at}, so of two
 * racing requests exactly one holds it. {@code reserved_at} is truncated to microseconds by the
 * caller so the value written is the value read back.
 */
@Component
class IdempotencyStore {

    /** A stored row with the answer it holds once done. */
    record Entry(Row row, Integer httpStatus, String contentType, byte[] responseBody) {}

    private static final RowMapper<Entry> ENTRY = IdempotencyStore::map;

    private final JdbcTemplate jdbc;

    IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Entry> find(UUID userId, String key) {
        return jdbc.query("""
                SELECT fingerprint, state, reserved_at, expires_at, http_status, content_type, response_body
                FROM api_token_idempotency WHERE user_id = ? AND idem_key = ?
                """, ENTRY, userId, key).stream().findFirst();
    }

    /** Whether this call created the row; false when another request already holds the key. */
    boolean reserve(UUID userId, String key, String fingerprint, Instant reservedAt, Instant expiresAt) {
        return jdbc.update("""
                INSERT INTO api_token_idempotency (user_id, idem_key, fingerprint, state, reserved_at, expires_at)
                VALUES (?, ?, ?, 'running', ?, ?) ON CONFLICT (user_id, idem_key) DO NOTHING
                """, userId, key, fingerprint, Timestamp.from(reservedAt), Timestamp.from(expiresAt)) == 1;
    }

    /** Replaces the row last seen at {@code seenReservedAt} (stale or expired); false when someone else got there first. */
    boolean takeOver(UUID userId, String key, Instant seenReservedAt, String fingerprint, Instant reservedAt,
            Instant expiresAt) {
        return jdbc.update("""
                UPDATE api_token_idempotency SET fingerprint = ?, state = 'running', reserved_at = ?, expires_at = ?,
                    http_status = NULL, content_type = NULL, response_body = NULL
                WHERE user_id = ? AND idem_key = ? AND reserved_at = ?
                """, fingerprint, Timestamp.from(reservedAt), Timestamp.from(expiresAt), userId, key,
                Timestamp.from(seenReservedAt)) == 1;
    }

    void complete(UUID userId, String key, Instant reservedAt, int httpStatus, String contentType, byte[] body) {
        jdbc.update("""
                UPDATE api_token_idempotency SET state = 'done', http_status = ?, content_type = ?, response_body = ?
                WHERE user_id = ? AND idem_key = ? AND reserved_at = ?
                """, httpStatus, contentType, body, userId, key, Timestamp.from(reservedAt));
    }

    /** Frees the key after a failed or oversized answer; leaves a reservation another request took over. */
    void release(UUID userId, String key, Instant reservedAt) {
        jdbc.update("DELETE FROM api_token_idempotency WHERE user_id = ? AND idem_key = ? AND reserved_at = ?"
                + " AND state = 'running'", userId, key, Timestamp.from(reservedAt));
    }

    private static Entry map(ResultSet row, int index) throws SQLException {
        int status = row.getInt("http_status");
        Integer httpStatus = row.wasNull() ? null : status;
        Row decisionRow = new Row(row.getString("fingerprint"),
                State.valueOf(row.getString("state").toUpperCase(Locale.ROOT)),
                row.getTimestamp("reserved_at").toInstant(), row.getTimestamp("expires_at").toInstant());
        return new Entry(decisionRow, httpStatus, row.getString("content_type"), row.getBytes("response_body"));
    }
}
