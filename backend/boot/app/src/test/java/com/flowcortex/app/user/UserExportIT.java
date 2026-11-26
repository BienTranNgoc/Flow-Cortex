package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.audit.JdbcAdminAuditWriter;
import com.flowcortex.user.pii.PiiMask;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /api/v1/users/export} with a two-row limit, against the real, fully-wired app. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = "flowcortex.users.export.max-rows=2")
class UserExportIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String BOM = "﻿";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private JdbcAdminAuditWriter writer;

    private List<Map<String, Object>> exportRows(UserView actor) {
        return jdbc.queryForList("SELECT *, change_summary::text AS summary_text FROM admin_audit_event"
                + " WHERE actor_user_id = ? AND action = 'user.export'", actor.id());
    }

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private UserView seed(UserView admin, String name, String role) {
        return userService.createUser(admin.id(), uniqueEmail("exp"), PASSWORD, name, role);
    }

    private ResponseEntity<byte[]> export(UserView caller, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login(caller.email(), PASSWORD));
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv"), MediaType.APPLICATION_PROBLEM_JSON));
        return restTemplate.exchange("/api/v1/users/export" + query, HttpMethod.GET, new HttpEntity<>(headers),
                byte[].class);
    }

    private static String text(ResponseEntity<byte[]> response) {
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    @Test
    void overTheLimitIsUnprocessableWithTheCountAndNoFile() {
        UserView admin = seedAdmin("exp-over");
        seed(admin, "One", "MEMBER");
        seed(admin, "Two", "MEMBER");

        ResponseEntity<byte[]> response = export(admin, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getHeaders().containsHeader(HttpHeaders.CONTENT_DISPOSITION)).isFalse();
        String body = text(response);
        assertThat(body).contains("\"code\":\"user.exportTooLarge\"").contains("3").contains("2");
    }

    @Test
    void atTheLimitTheFileHoldsEveryMatch() {
        UserView admin = seedAdmin("exp-at");
        UserView one = seed(admin, "Lê Thị \"Bé\"", "MEMBER");

        ResponseEntity<byte[]> response = export(admin, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = text(response);
        assertThat(body).startsWith(BOM + "ID,Name,Email,");
        String[] lines = body.split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(body).contains("\"" + one.id() + "\",\"Lê Thị \"\"Bé\"\"\"");
    }

    @Test
    void theFileHonoursTheListFilters() {
        UserView admin = seedAdmin("exp-filter");
        UserView suspended = seed(admin, "Suspended", "MEMBER");
        seed(admin, "Active", "MEMBER");
        jdbc.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", suspended.id());

        ResponseEntity<byte[]> response = export(admin, "?state=suspended");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String[] lines = text(response).split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains(suspended.id().toString()).contains("\"suspended\"");
        List<Map<String, Object>> rows = exportRows(admin);
        assertThat(rows).hasSize(1);
        assertThat(JSON.readTree((String) rows.get(0).get("summary_text")))
                .isEqualTo(JSON.readTree("{\"state\":\"suspended\",\"rowCount\":1}"));
    }

    @Test
    void anUnknownRoleFilterIsABadRequest() {
        UserView admin = seedAdmin("exp-role");

        ResponseEntity<byte[]> response = export(admin, "?role=NO_SUCH_ROLE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(response)).contains("\"code\":\"role.unknown\"");
    }

    @Test
    void customerSupportIsForbidden() {
        UserView admin = seedAdmin("exp-cs");
        UserView support = seed(admin, "Support", "CUSTOMER_SUPPORT");

        assertThat(export(support, "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void operationManagerExportsTheirAdminsScope() {
        UserView admin = seedAdmin("exp-om");
        UserView manager = seed(admin, "Manager", "OPERATION_MANAGER");

        ResponseEntity<byte[]> response = export(manager, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = text(response);
        assertThat(body).contains(admin.id().toString()).contains(manager.id().toString());
        assertThat(body).contains(admin.email()).contains(manager.email()).contains("\"Manager\"");
        List<Map<String, Object>> rows = exportRows(manager);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("resource_type")).isEqualTo("user");
        assertThat(row.get("status")).isEqualTo("success");
        assertThat(JSON.readTree((String) row.get("summary_text"))).isEqualTo(JSON.readTree("{\"rowCount\":2}"));
    }

    @Test
    void exportKeyWithoutThePiiKey() {
        UserView admin = seedAdmin("exp-nopii");
        String code = "IT_EXPORT_NO_PII";
        jdbc.update("INSERT INTO role (code, name, staff, super_admin, system, sort_order) "
                + "VALUES (?, 'Export without PII', true, false, false, 1000)", code);
        for (Permission key : List.of(Permission.USER_ACCOUNT_VIEW, Permission.USER_ACCOUNT_EXPORT)) {
            jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES (?, ?)", code, key.key());
        }
        try {
            UserView holder = seed(admin, "Lê Thị Lan", code);

            ResponseEntity<byte[]> response = export(holder, "");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            String body = text(response);
            assertThat(body).contains("\"L*** T*** L***\"").contains(PiiMask.email(holder.email()))
                    .contains(PiiMask.email(admin.email()));
            assertThat(body).doesNotContain(holder.email()).doesNotContain(admin.email())
                    .doesNotContain("Lê Thị Lan");
            assertThat(exportRows(holder)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM app_user WHERE role = ?", code);
            jdbc.update("DELETE FROM role WHERE code = ?", code);
        }
    }

    @Test
    void recordingFailsSendsNoFile() {
        UserView admin = seedAdmin("exp-503");
        doThrow(new IllegalStateException("insert failed")).when(writer).writeOrThrow(any());

        ResponseEntity<byte[]> response = export(admin, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getHeaders().containsHeader(HttpHeaders.CONTENT_DISPOSITION)).isFalse();
        assertThat(text(response)).contains("audit trail could not record").doesNotContain(admin.email());
        assertThat(exportRows(admin)).isEmpty();
    }

    @Test
    void contentDispositionNamesADatedCsv() {
        UserView admin = seedAdmin("exp-name");

        ResponseEntity<byte[]> response = export(admin, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv").containsIgnoringCase(
                "charset=utf-8");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .matches("attachment; filename=\"users-\\d{8}-\\d{4}\\.csv\"");
    }
}
