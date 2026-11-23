package com.flowcortex.user.oauth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code oauth_issued_token}: which personal access tokens this flow minted, and for which client. */
@Component
class OAuthIssuedTokenRepository {

    private final JdbcTemplate jdbc;

    OAuthIssuedTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tokenId, String clientId, Instant issuedAt) {
        jdbc.update("INSERT INTO oauth_issued_token (token_id, client_id, issued_at) VALUES (?, ?, ?)", tokenId,
                clientId, Timestamp.from(issuedAt));
    }

    boolean issuedTo(UUID tokenId, String clientId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM oauth_issued_token WHERE token_id = ? AND client_id = ?", Integer.class, tokenId,
                clientId);
        return count != null && count > 0;
    }
}
