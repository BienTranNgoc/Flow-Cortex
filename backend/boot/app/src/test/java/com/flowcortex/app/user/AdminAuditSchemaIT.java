package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The {@code admin_audit_event} constraints against the real schema. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminAuditSchemaIT extends AbstractUserModuleIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aRowInsertedWithoutOriginIsUnknown() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_audit_event (id, actor_user_id, action, resource_type, status, occurred_at)"
                + " VALUES (?, ?, 'user.create', 'user', 'success', now())", id, UUID.randomUUID());

        assertThat(jdbc.queryForObject("SELECT origin_source FROM admin_audit_event WHERE id = ?", String.class, id))
                .isEqualTo("unknown");
    }

    @Test
    void anUnknownOriginIsRefused() {
        assertThatThrownBy(() -> insert("success", "cron")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aStatusOutsideSuccessOrFailedIsRefused() {
        assertThatThrownBy(() -> insert("pending", "http")).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(String status, String origin) {
        jdbc.update("INSERT INTO admin_audit_event (id, actor_user_id, action, resource_type, status, origin_source,"
                + " occurred_at) VALUES (?, ?, 'user.create', 'user', ?, ?, now())",
                UUID.randomUUID(), UUID.randomUUID(), status, origin);
    }
}
