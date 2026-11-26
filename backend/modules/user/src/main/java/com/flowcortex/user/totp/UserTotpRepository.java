package com.flowcortex.user.totp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code user_totp}. Every write that decides an outcome is one conditional statement whose row count (or
 * returned row) is the answer, so two requests at once cannot both win.
 */
@Repository
public class UserTotpRepository {

    private static final RowMapper<UserTotp> ROW = (rs, i) -> new UserTotp(
            rs.getObject("user_id", UUID.class),
            rs.getString("secret_encrypted"),
            instant(rs.getTimestamp("enabled_at")),
            rs.getObject("last_used_step", Long.class),
            rs.getInt("failed_attempts"),
            instant(rs.getTimestamp("locked_until")));

    private static final String SELECT = "SELECT user_id, secret_encrypted, enabled_at, last_used_step, "
            + "failed_attempts, locked_until FROM user_totp";

    private final JdbcTemplate jdbc;

    UserTotpRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What a failed code left on the row. {@code lockedUntil} is set only on the failure that locked the
     * account, and the count is then back at zero.
     */
    public record FailureCount(int failedAttempts, Instant lockedUntil) {
    }

    Optional<UserTotp> findByUserId(UUID userId) {
        return jdbc.query(SELECT + " WHERE user_id = ?", ROW, userId).stream().findFirst();
    }

    /**
     * Reads the row and holds its lock until the surrounding transaction ends, so every check of a code
     * against one account runs one after another and each sees the failures and the lock the one before wrote.
     */
    Optional<UserTotp> findForUpdate(UUID userId) {
        return jdbc.query(SELECT + " WHERE user_id = ? FOR UPDATE", ROW, userId).stream().findFirst();
    }

    /** True when the account has TOTP on; a setup that is not confirmed yet does not count. */
    public boolean isEnabled(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_totp WHERE user_id = ? AND enabled_at IS NOT NULL)",
                Boolean.class, userId));
    }

    /**
     * Stores the secret of a setup that is not confirmed yet, replacing an earlier pending one. Returns 0 when
     * the account is already enabled. The failure count and the lock are kept, so starting a new setup does
     * not clear a lock earned by wrong codes.
     */
    public int savePending(UUID userId, String secretEncrypted) {
        return jdbc.update("INSERT INTO user_totp (user_id, secret_encrypted) VALUES (?, ?) "
                + "ON CONFLICT (user_id) DO UPDATE SET secret_encrypted = EXCLUDED.secret_encrypted, "
                + "updated_at = now() WHERE user_totp.enabled_at IS NULL", userId, secretEncrypted);
    }

    /** Turns a pending setup on and records the step of the confirming code; 0 when it is not pending. */
    public int enable(UUID userId, long step) {
        return jdbc.update("UPDATE user_totp SET enabled_at = now(), last_used_step = ?, updated_at = now() "
                + "WHERE user_id = ? AND enabled_at IS NULL", step, userId);
    }

    /**
     * Accepts {@code step} for an enabled account only if it is newer than the last accepted one and the
     * account is not locked at {@code now}; else 0.
     */
    public int claimStep(UUID userId, long step, Instant now) {
        return jdbc.update("UPDATE user_totp SET last_used_step = ?, updated_at = now() WHERE user_id = ? "
                + "AND enabled_at IS NOT NULL AND (last_used_step IS NULL OR last_used_step < ?) "
                + "AND (locked_until IS NULL OR locked_until <= ?)",
                step, userId, step, Timestamp.from(now));
    }

    /**
     * Counts one wrong code. The {@code max}-th failure locks the row until {@code lockedUntil} and resets the
     * count. Empty while the account is locked at {@code now}: a locked account counts nothing further.
     */
    public Optional<FailureCount> recordFailure(UUID userId, int max, Instant lockedUntil, Instant now) {
        return jdbc.query("UPDATE user_totp SET "
                + "failed_attempts = CASE WHEN failed_attempts + 1 >= ? THEN 0 ELSE failed_attempts + 1 END, "
                + "locked_until = CASE WHEN failed_attempts + 1 >= ? THEN CAST(? AS timestamptz) ELSE NULL END, "
                + "updated_at = ? WHERE user_id = ? AND (locked_until IS NULL OR locked_until <= ?) "
                + "RETURNING failed_attempts, locked_until",
                (rs, i) -> new FailureCount(rs.getInt("failed_attempts"), instant(rs.getTimestamp("locked_until"))),
                max, max, Timestamp.from(lockedUntil), Timestamp.from(now), userId, Timestamp.from(now))
                .stream().findFirst();
    }

    /** Clears the failure count after a correct code; the lock, if any, is left to expire. */
    public void resetFailures(UUID userId) {
        jdbc.update("UPDATE user_totp SET failed_attempts = 0, updated_at = now() "
                + "WHERE user_id = ? AND failed_attempts <> 0", userId);
    }

    public int delete(UUID userId) {
        return jdbc.update("DELETE FROM user_totp WHERE user_id = ?", userId);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
