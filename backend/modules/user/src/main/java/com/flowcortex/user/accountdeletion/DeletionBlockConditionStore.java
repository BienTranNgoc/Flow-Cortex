package com.flowcortex.user.accountdeletion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code deletion_block_condition}: the operator-editable switch, order and text of each blocking condition. */
@Component
class DeletionBlockConditionStore {

    /** What a registered blocker contributes for a condition no row exists for yet. */
    record Defaults(String key, int sortOrder, String messageVi, String messageEn) {
    }

    record Condition(
            String key,
            boolean enabled,
            int sortOrder,
            String messageVi,
            String messageEn,
            UUID updatedBy,
            Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;

    DeletionBlockConditionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a row, enabled, for every key that has none; a row an operator edited is never touched. */
    void insertMissing(List<Defaults> defaults) {
        for (Defaults row : defaults) {
            jdbc.update("INSERT INTO deletion_block_condition (key, enabled, sort_order, message_vi, message_en, "
                    + "updated_by, updated_at) VALUES (?, true, ?, ?, ?, NULL, now()) ON CONFLICT (key) DO NOTHING",
                    row.key(), row.sortOrder(), row.messageVi(), row.messageEn());
        }
    }

    List<Condition> findAll() {
        return jdbc.query("SELECT key, enabled, sort_order, message_vi, message_en, updated_by, updated_at "
                + "FROM deletion_block_condition ORDER BY sort_order, key", (row, i) -> map(row));
    }

    /** Rewrites one row; the number of rows changed, {@code 0} when the key has no row. */
    int update(String key, boolean enabled, int sortOrder, String messageVi, String messageEn, UUID actor,
            Instant at) {
        return jdbc.update("UPDATE deletion_block_condition SET enabled = ?, sort_order = ?, message_vi = ?, "
                + "message_en = ?, updated_by = ?, updated_at = ? WHERE key = ?",
                enabled, sortOrder, messageVi, messageEn, actor, Timestamp.from(at), key);
    }

    private static Condition map(ResultSet row) throws SQLException {
        return new Condition(
                row.getString("key"),
                row.getBoolean("enabled"),
                row.getInt("sort_order"),
                row.getString("message_vi"),
                row.getString("message_en"),
                row.getObject("updated_by", UUID.class),
                row.getTimestamp("updated_at").toInstant());
    }
}
