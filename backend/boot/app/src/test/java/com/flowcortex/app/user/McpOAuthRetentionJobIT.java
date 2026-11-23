package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.oauth.McpOAuthRetentionJob;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The retention job against the real schema, switch off (the job runs either way): which authorization
 * codes, clients, call rows and idempotency rows it deletes, and that a second run changes nothing. The
 * cluster runner is off, so the test calls {@code run()}.
 */
@Testcontainers
@SpringBootTest
class McpOAuthRetentionJobIT extends AbstractPostgresBootTest {

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private McpOAuthRetentionJob job;

    @Test
    void aCodeRedeemedTwoDaysAgoOrExpiredTwoDaysAgoIsDeletedAndAFreshOneStays() {
        UUID userId = admin();
        String clientId = client(1);
        String redeemed = code(clientId, userId, "now() + interval '1 hour'", "now() - interval '2 days'");
        String expired = code(clientId, userId, "now() - interval '2 days'", "NULL");
        String fresh = code(clientId, userId, "now() + interval '3 minutes'", "NULL");
        String redeemedToday = code(clientId, userId, "now() + interval '1 hour'", "now() - interval '2 hours'");

        job.run();

        assertThat(codes(clientId)).containsExactlyInAnyOrder(fresh, redeemedToday)
                .doesNotContain(redeemed, expired);
    }

    @Test
    void anOldClientIsDeletedUnlessAnIssuedTokenIsStillStored() {
        UUID userId = admin();
        String clientA = client(31);
        String clientB = client(31);
        String clientC = client(2);
        UUID token = token(userId);
        jdbc.update("INSERT INTO oauth_issued_token (token_id, client_id, issued_at) VALUES (?, ?, now())", token,
                clientB);

        job.run();

        assertThat(clientExists(clientA)).isFalse();
        assertThat(clientExists(clientB)).isTrue();
        assertThat(clientExists(clientC)).isTrue();

        jdbc.update("DELETE FROM personal_access_token WHERE id = ?", token);
        job.run();

        assertThat(clientExists(clientB)).isFalse();
        assertThat(clientExists(clientC)).isTrue();
    }

    @Test
    void anOldCallRowAndAnEndedIdempotencyWindowArePurgedAndTheRecentOnesStay() {
        UUID owner = UUID.randomUUID();
        UUID oldCall = call(owner, "91 days");
        UUID recentCall = call(owner, "1 day");
        String endedKey = idempotency(owner, "now() - interval '1 hour'");
        String openKey = idempotency(owner, "now() + interval '23 hours'");

        job.run();

        assertThat(jdbc.queryForList("SELECT id FROM api_token_call WHERE user_id = ?", UUID.class, owner))
                .containsExactly(recentCall)
                .doesNotContain(oldCall);
        assertThat(jdbc.queryForList("SELECT idem_key FROM api_token_idempotency WHERE user_id = ?", String.class,
                owner)).containsExactly(openKey).doesNotContain(endedKey);
    }

    @Test
    void aSecondRunChangesNothing() {
        UUID userId = admin();
        String oldClient = client(40);
        String keptClient = client(1);
        code(oldClient, userId, "now() - interval '3 days'", "NULL");
        String keptCode = code(keptClient, userId, "now() + interval '3 minutes'", "NULL");
        UUID owner = UUID.randomUUID();
        call(owner, "100 days");
        call(owner, "1 day");
        idempotency(owner, "now() - interval '2 hours'");
        idempotency(owner, "now() + interval '2 hours'");

        job.run();
        long clients = count("oauth_client");
        long codes = count("oauth_authorization_code");
        long calls = count("api_token_call");
        long keys = count("api_token_idempotency");
        job.run();

        assertThat(count("oauth_client")).isEqualTo(clients);
        assertThat(count("oauth_authorization_code")).isEqualTo(codes);
        assertThat(count("api_token_call")).isEqualTo(calls);
        assertThat(count("api_token_idempotency")).isEqualTo(keys);
        assertThat(clientExists(oldClient)).isFalse();
        assertThat(codes(keptClient)).containsExactly(keptCode);
    }

    private UUID admin() {
        return userService.createInitialAdmin("mcp-retention-" + UUID.randomUUID() + "@example.com", "password123",
                "Retention").id();
    }

    private String client(int ageDays) {
        String clientId = "mcp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update("INSERT INTO oauth_client (client_id, client_secret_hash, client_name, redirect_uris, auth_method,"
                + " created_at) VALUES (?, NULL, 'Retention client', '[\"https://claude.ai/api/mcp/auth_callback\"]'::jsonb,"
                + " 'none', now() - make_interval(days => ?))", clientId, ageDays);
        return clientId;
    }

    private boolean clientExists(String clientId) {
        return jdbc.queryForObject("SELECT count(*) FROM oauth_client WHERE client_id = ?", Long.class, clientId) == 1;
    }

    private String code(String clientId, UUID userId, String expiresAt, String redeemedAt) {
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO oauth_authorization_code (code_hash, client_id, user_id, redirect_uri, code_challenge,"
                + " scope, created_at, expires_at, redeemed_at) VALUES (?, ?, ?,"
                + " 'https://claude.ai/api/mcp/auth_callback', 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM', 'mcp',"
                + " now() - interval '3 days', " + expiresAt + ", " + redeemedAt + ")", hash, clientId, userId);
        return hash;
    }

    private List<String> codes(String clientId) {
        return jdbc.queryForList("SELECT code_hash FROM oauth_authorization_code WHERE client_id = ?", String.class,
                clientId);
    }

    private UUID token(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO personal_access_token (id, user_id, name, token_hash, created_at, expires_at)"
                + " VALUES (?, ?, 'MCP: Retention', ?, now(), now() + interval '90 days')", id, userId,
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        return id;
    }

    private UUID call(UUID owner, String age) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_token_call (id, user_id, token_id, method, route, outcome, http_status,"
                + " duration_ms, occurred_at) VALUES (?, ?, ?, 'POST', '/api/v1/workflows', 'ok', 201, 5,"
                + " now() - interval '" + age + "')", id, owner, UUID.randomUUID());
        return id;
    }

    private String idempotency(UUID owner, String expiresAt) {
        String key = "k-" + UUID.randomUUID();
        jdbc.update("INSERT INTO api_token_idempotency (user_id, idem_key, fingerprint, state, reserved_at, http_status,"
                + " expires_at) VALUES (?, ?, 'f', 'done', now() - interval '25 hours', 201, " + expiresAt + ")",
                owner, key);
        return key;
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
