package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A request refused by the admin kill switch never reaches the audit interceptor. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.admin.enabled=false",
        "flowcortex.support.enabled=true"})
@AutoConfigureTestRestTemplate
class AdminAuditKillSwitchIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void killSwitchOff() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("kill-switch"), PASSWORD, "Kill Switch");

        ResponseEntity<String> response = put("/api/v1/admin/support/settings", login(admin.email(), PASSWORD),
                Map.of("assistantEnabled", false), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_event WHERE actor_user_id = ?",
                Long.class, admin.id())).isZero();
    }
}
