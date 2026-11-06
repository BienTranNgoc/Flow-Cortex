package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.application.AuthRetentionJob;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The retention job's reset-link purge against the real schema: which emailed-link rows it deletes
 * and which it leaves, for one account holding links of both purposes.
 */
@Testcontainers
@SpringBootTest
class AuthRetentionJobIT extends AbstractPostgresBootTest {

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthRetentionJob retentionJob;

    @Test
    void thePurgeDeletesExpiredAndUsedResetLinksAndKeepsAnOpenOneAndOtherPurposes() {
        UUID userId = userService.createInitialAdmin("retention-links-" + UUID.randomUUID() + "@example.com",
                "password123", "Retention Links").id();
        UUID expiredReset = link(userId, "PASSWORD_RESET", "now() - interval '1 hour'", "NULL");
        UUID usedReset = link(userId, "PASSWORD_RESET", "now() + interval '1 hour'", "now() - interval '5 minutes'");
        UUID openReset = link(userId, "PASSWORD_RESET", "now() + interval '1 hour'", "NULL");
        UUID usedConfirm = link(userId, "EMAIL_CONFIRM", "now() + interval '1 hour'", "now() - interval '5 minutes'");

        retentionJob.purge();

        assertThat(jdbc.queryForList("SELECT id FROM email_confirmation_token WHERE user_id = ?", UUID.class, userId))
                .containsExactlyInAnyOrder(openReset, usedConfirm)
                .doesNotContain(expiredReset, usedReset);
    }

    private UUID link(UUID userId, String purpose, String expiresAt, String usedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO email_confirmation_token (id, user_id, token_hash, expires_at, used_at, created_at,"
                + " purpose) VALUES (?, ?, ?, " + expiresAt + ", " + usedAt + ", now() - interval '2 hours', ?)",
                id, userId, "retention-" + id, purpose);
        return id;
    }
}
