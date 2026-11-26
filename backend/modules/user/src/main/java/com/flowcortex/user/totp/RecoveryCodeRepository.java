package com.flowcortex.user.totp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code user_recovery_code}: a code is spent by deleting its row, so a spent code leaves nothing to purge. */
@Repository
public class RecoveryCodeRepository {

    /** The hex keyed hash of a normalized recovery code and the id of the key it was made under. */
    public record KeyedHash(String keyId, String hash) {
    }

    private final JdbcTemplate jdbc;

    RecoveryCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Replaces the account's whole set; the old and the new set never coexist. */
    @Transactional
    public void replaceAll(UUID userId, List<KeyedHash> codes) {
        deleteAll(userId);
        jdbc.batchUpdate("INSERT INTO user_recovery_code (id, user_id, key_id, code_hash) VALUES (?, ?, ?, ?)",
                codes, codes.size(), (statement, code) -> {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, userId);
                    statement.setString(3, code.keyId());
                    statement.setString(4, code.hash());
                });
    }

    /**
     * True for exactly one of any number of simultaneous attempts with the same code, and never while the
     * account's second factor is locked at {@code now}.
     */
    public boolean consume(UUID userId, String keyId, String hash, Instant now) {
        return jdbc.update("DELETE FROM user_recovery_code WHERE user_id = ? AND key_id = ? AND code_hash = ? "
                + "AND NOT EXISTS (SELECT 1 FROM user_totp WHERE user_id = ? AND locked_until > ?)",
                userId, keyId, hash, userId, Timestamp.from(now)) == 1;
    }

    public int countFor(UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM user_recovery_code WHERE user_id = ?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    public int deleteAll(UUID userId) {
        return jdbc.update("DELETE FROM user_recovery_code WHERE user_id = ?", userId);
    }
}
