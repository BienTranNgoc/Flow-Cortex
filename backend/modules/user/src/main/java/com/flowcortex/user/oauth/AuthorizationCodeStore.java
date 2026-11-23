package com.flowcortex.user.oauth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** {@code oauth_authorization_code}: redemption is one conditional write, so only one caller can win it. */
@Component
class AuthorizationCodeStore {

    private static final String COLUMNS = "code_hash, client_id, user_id, redirect_uri, code_challenge, scope,"
            + " created_at, expires_at, redeemed_at, issued_token_id";

    private static final RowMapper<AuthorizationCode> ROW = AuthorizationCodeStore::map;

    private final JdbcTemplate jdbc;

    AuthorizationCodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(AuthorizationCode code) {
        jdbc.update("INSERT INTO oauth_authorization_code (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)",
                code.codeHash(), code.clientId(), code.userId(), code.redirectUri(), code.codeChallenge(),
                code.scope(), Timestamp.from(code.createdAt()), Timestamp.from(code.expiresAt()));
    }

    /**
     * Marks the code redeemed when it is unredeemed and unexpired and returns it; empty otherwise. A
     * second caller racing on the same row waits for the first to commit, then matches nothing.
     */
    Optional<AuthorizationCode> redeem(String codeHash, Instant now) {
        return jdbc.query("UPDATE oauth_authorization_code SET redeemed_at = ? WHERE code_hash = ?"
                + " AND redeemed_at IS NULL AND expires_at > ? RETURNING " + COLUMNS, ROW, Timestamp.from(now),
                codeHash, Timestamp.from(now)).stream().findFirst();
    }

    Optional<AuthorizationCode> find(String codeHash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM oauth_authorization_code WHERE code_hash = ?", ROW, codeHash)
                .stream().findFirst();
    }

    void markIssued(String codeHash, UUID tokenId) {
        jdbc.update("UPDATE oauth_authorization_code SET issued_token_id = ? WHERE code_hash = ?", tokenId, codeHash);
    }

    private static AuthorizationCode map(ResultSet row, int index) throws SQLException {
        Timestamp redeemedAt = row.getTimestamp("redeemed_at");
        return new AuthorizationCode(row.getString("code_hash"), row.getString("client_id"),
                row.getObject("user_id", UUID.class), row.getString("redirect_uri"), row.getString("code_challenge"),
                row.getString("scope"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(), redeemedAt == null ? null : redeemedAt.toInstant(),
                row.getObject("issued_token_id", UUID.class));
    }
}
