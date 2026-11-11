package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.audit.AdminAuditRetentionJob;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The origin sweep against the real schema; the cluster runner is off, so the test calls {@code run()}. */
@Testcontainers
@SpringBootTest
class AdminAuditRetentionJobIT extends AbstractPostgresBootTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminAuditRetentionJob job;

    @Value("${flowcortex.admin-audit.retention-interval}")
    private Duration configuredInterval;

    @Test
    void jobNameAndIntervalComeFromConfig() {
        assertThat(job.name()).isEqualTo("user.admin-audit-origin-retention");
        assertThat(job.interval()).isEqualTo(configuredInterval);
    }

    @Test
    void anOldRowLosesOnlyItsAddressAndUserAgent() {
        UUID id = insert(400);
        Map<String, Object> before = row(id);

        job.run();

        Map<String, Object> after = row(id);
        assertThat(after.get("ip")).isNull();
        assertThat(after.get("user_agent")).isNull();
        before.remove("ip");
        before.remove("user_agent");
        after.remove("ip");
        after.remove("user_agent");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void aRecentRowIsUntouched() {
        UUID id = insert(10);

        job.run();

        Map<String, Object> after = row(id);
        assertThat(after.get("ip")).isEqualTo("203.0.113.7");
        assertThat(after.get("user_agent")).isEqualTo("JUnit/1.0");
    }

    @Test
    void runningTwiceGivesTheSameResult() {
        UUID id = insert(400);
        job.run();
        Map<String, Object> afterFirst = row(id);

        job.run();

        assertThat(row(id)).isEqualTo(afterFirst);
    }

    private UUID insert(int ageDays) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_audit_event (id, actor_user_id, actor_email, actor_name, action, resource_type,"
                + " resource_id, resource_label, change_summary, status, error_code, ip, user_agent, origin_source,"
                + " occurred_at) VALUES (?, ?, 'a@example.com', 'Admin', 'user.update', 'user', '42', 'Label',"
                + " '{\"role\":\"CREATOR\"}'::jsonb, 'failed', 'forbidden', '203.0.113.7', 'JUnit/1.0', 'http',"
                + " now() - make_interval(days => ?))", id, UUID.randomUUID(), ageDays);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM admin_audit_event WHERE id = ?", id);
        assertThat(rows).hasSize(1);
        return new HashMap<>(rows.get(0));
    }
}
