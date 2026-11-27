package com.flowcortex.user.totp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code login_challenge}. A challenge is live while it is unspent, unexpired and under its wrong-code limit;
 * the limit is passed in because it is configuration, and every time check uses the caller's clock.
 */
@Repository
public class LoginChallengeRepository {

    private final JdbcTemplate jdbc;

    LoginChallengeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a challenge under the hash of its cookie value and returns its id. */
    public UUID insert(UUID userId, String tokenHash, boolean rememberMe, ChallengeOrigin origin,
            Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO login_challenge (id, user_id, token_hash, remember_me, origin, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, userId, tokenHash, rememberMe, origin.column(),
                Timestamp.from(expiresAt));
        return id;
    }

    public Optional<LoginChallenge> findLive(String tokenHash, Instant now, int maxWrongCodes) {
        return jdbc.query("SELECT id, user_id, remember_me, origin, expires_at, failed_codes FROM login_challenge "
                + "WHERE token_hash = ? AND spent_at IS NULL AND expires_at > ? AND failed_codes < ?",
                (rs, i) -> new LoginChallenge(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getBoolean("remember_me"), ChallengeOrigin.fromColumn(rs.getString("origin")),
                        rs.getTimestamp("expires_at").toInstant(), rs.getInt("failed_codes")),
                tokenHash, Timestamp.from(now), maxWrongCodes).stream().findFirst();
    }

    /** True for exactly one of any number of simultaneous completions, and never for a dead challenge. */
    public boolean spend(UUID id, Instant now, int maxWrongCodes) {
        return jdbc.update("UPDATE login_challenge SET spent_at = ? WHERE id = ? AND spent_at IS NULL "
                + "AND expires_at > ? AND failed_codes < ?", Timestamp.from(now), id, Timestamp.from(now),
                maxWrongCodes) == 1;
    }

    /** Counts one wrong code and returns how many are left; 0 once the challenge is dead, whatever its cause. */
    public int recordWrongCode(UUID id, Instant now, int maxWrongCodes) {
        return jdbc.query("UPDATE login_challenge SET failed_codes = failed_codes + 1 WHERE id = ? "
                + "AND spent_at IS NULL AND expires_at > ? AND failed_codes < ? RETURNING failed_codes",
                (rs, i) -> maxWrongCodes - rs.getInt("failed_codes"), id, Timestamp.from(now), maxWrongCodes)
                .stream().findFirst().orElse(0);
    }

    /** Deletes the account's unspent challenges, so a disable or a reset leaves no half-finished sign-in. */
    public int deleteLiveFor(UUID userId) {
        return jdbc.update("DELETE FROM login_challenge WHERE user_id = ? AND spent_at IS NULL", userId);
    }

    /** Deletes expired and spent challenges. */
    public int deleteDead(Instant now) {
        return jdbc.update("DELETE FROM login_challenge WHERE expires_at <= ? OR spent_at IS NOT NULL",
                Timestamp.from(now));
    }
}
