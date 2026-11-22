package com.flowcortex.settings.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The two settings tables: the stored value of each key and the history of its changes. */
@Repository
public class SettingStore {

    /** A stored value as text, with the stamp of whoever stored it. */
    public record StoredSetting(String value, UUID updatedBy, Instant updatedAt) {
    }

    /** One history row, values as the text that was stored. */
    public record StoredChange(UUID id, String key, String previousValue, String newValue, UUID changedBy,
            String reason, Instant changedAt) {
    }

    private static final String CHANGE_COLUMNS =
            "id, key, previous_value, new_value, changed_by, reason, changed_at";

    private final JdbcTemplate jdbc;

    public SettingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredSetting> find(String key) {
        return jdbc.query("SELECT value, updated_by, updated_at FROM app_setting WHERE key = ?",
                (rs, row) -> new StoredSetting(rs.getString("value"), uuid(rs, "updated_by"),
                        rs.getTimestamp("updated_at").toInstant()), key)
                .stream().findFirst();
    }

    /** Holds a transaction-scoped lock on the key, which also serializes the first write where no row exists. */
    public void lockKey(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('setting:' || ?, 0))", rs -> {
        }, key);
    }

    public void upsert(String key, String value, UUID actor, Instant at) {
        jdbc.update("""
                INSERT INTO app_setting (key, value, updated_by, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (key) DO UPDATE
                SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """, key, value, actor, Timestamp.from(at));
    }

    public void delete(String key) {
        jdbc.update("DELETE FROM app_setting WHERE key = ?", key);
    }

    /** {@code reason} is stored as given; the caller has already trimmed it and bounded its length. */
    public void insertChange(UUID id, String key, String previous, String next, UUID actor, Instant at,
            String reason) {
        jdbc.update("""
                INSERT INTO app_setting_change (id, key, previous_value, new_value, changed_by, changed_at, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, key, previous, next, actor, Timestamp.from(at), reason);
    }

    /** Newest first; ties on the time fall back to the id so the order is total. */
    public List<StoredChange> history(String key, int page, int size) {
        return jdbc.query("SELECT " + CHANGE_COLUMNS + """
                 FROM app_setting_change
                WHERE key = ? ORDER BY changed_at DESC, id DESC LIMIT ? OFFSET ?
                """, SettingStore::change, key, size, (long) page * size);
    }

    public long countHistory(String key) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM app_setting_change WHERE key = ?", Long.class, key);
        return total == null ? 0 : total;
    }

    /** The changes of any of the keys, newest first with the same total order as {@link #history}. */
    public List<StoredChange> historyForKeys(Collection<String> keys, int page, int size) {
        return jdbc.query("SELECT " + CHANGE_COLUMNS + """
                 FROM app_setting_change
                WHERE key = ANY(?) ORDER BY changed_at DESC, id DESC LIMIT ? OFFSET ?
                """, ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("text", keys.toArray()));
                    ps.setInt(2, size);
                    ps.setLong(3, (long) page * size);
                }, SettingStore::change);
    }

    public long countHistoryForKeys(Collection<String> keys) {
        Long total = jdbc.query("SELECT count(*) FROM app_setting_change WHERE key = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", keys.toArray())),
                rs -> rs.next() ? rs.getLong(1) : 0L);
        return total == null ? 0 : total;
    }

    private static StoredChange change(ResultSet rs, int row) throws SQLException {
        return new StoredChange(uuid(rs, "id"), rs.getString("key"), rs.getString("previous_value"),
                rs.getString("new_value"), uuid(rs, "changed_by"), rs.getString("reason"),
                rs.getTimestamp("changed_at").toInstant());
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
