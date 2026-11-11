package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Audit rows of the user, admin-account and admin-workflow write routes over real HTTP. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminAuditUserRoutesIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String ACTIVE_SUPER_ADMIN =
            "role = 'ADMIN' AND status = 'ACTIVE' AND admin_disabled_at IS NULL";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    record IdView(UUID id, String name) {
    }

    record WorkflowRequest(String name, Boolean shared) {
    }

    record StepRequest(String type) {
    }

    record StepEditRequest(String name) {
    }

    record RoleRequest(String role) {
    }

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private UserView seedUser(UserView admin, String label, String role) {
        return userService.createUser(admin.id(), uniqueEmail(label), PASSWORD, label, role);
    }

    private List<Map<String, Object>> rows(UserView actor, String action) {
        return jdbc.queryForList("SELECT *, change_summary::text AS summary_text FROM admin_audit_event"
                + " WHERE actor_user_id = ? AND action = ? ORDER BY occurred_at", actor.id(), action);
    }

    private Map<String, Object> onlyRow(UserView actor, String action) {
        List<Map<String, Object>> rows = rows(actor, action);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static JsonNode summaryOf(Map<String, Object> row) {
        return JSON.readTree((String) row.get("summary_text"));
    }

    private <T> ResponseEntity<T> patch(String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers), type);
    }

    @Test
    void aUsersEmailAndNameAreNeverStored() {
        UserView admin = seedAdmin("audit-user-update");
        UserView member = seedUser(admin, "audit-update-target", "MEMBER");
        String newEmail = uniqueEmail("renamed");

        ResponseEntity<String> response = put("/api/v1/users/" + member.id(), login(admin.email(), PASSWORD),
                new UpdateUserRequestView(newEmail, "Tên Mới", "CREATOR", null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = onlyRow(admin, "user.update");
        assertThat(summaryOf(row)).isEqualTo(JSON.readTree("{\"role\":\"CREATOR\"}"));
        assertThat(row.get("summary_text").toString()).doesNotContain(newEmail).doesNotContain("Tên Mới");
        assertThat(row.get("resource_id")).isEqualTo(member.id().toString());
        assertThat(row.get("status")).isEqualTo("success");
    }

    @Test
    void aSelfEditRecordsTheActorAsTheyWereBeforeTheChange() {
        UserView admin = seedAdmin("audit-self-edit");
        String newEmail = uniqueEmail("self-renamed");

        ResponseEntity<String> response = put("/api/v1/users/" + admin.id(), login(admin.email(), PASSWORD),
                new UpdateUserRequestView(newEmail, "Tên Sau Khi Đổi", null, null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = onlyRow(admin, "user.update");
        assertThat(row.get("actor_email")).isEqualTo(admin.email());
        assertThat(row.get("actor_name")).isEqualTo(admin.name());
    }

    @Test
    void idFromTheResponse() {
        UserView admin = seedAdmin("audit-user-create");
        String email = uniqueEmail("created");

        ResponseEntity<IdView> response = post("/api/v1/users", login(admin.email(), PASSWORD),
                new CreateUserRequestView(email, PASSWORD, "Người Mới", "MEMBER"), IdView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = onlyRow(admin, "user.create");
        assertThat(row.get("resource_type")).isEqualTo("user");
        assertThat(row.get("resource_id")).isEqualTo(response.getBody().id().toString());
        assertThat(row.get("resource_label")).isEqualTo("Người Mới");
        assertThat(summaryOf(row)).isEqualTo(JSON.readTree("{\"role\":\"MEMBER\"}"));
        assertThat(row.get("summary_text").toString()).doesNotContain(PASSWORD).doesNotContain(email);
    }

    @Test
    void aRoleChangeOnAdminAccountsIsRecorded() {
        UserView admin = seedAdmin("audit-role-change");
        UserView manager = seedUser(admin, "audit-role-target", "OPERATION_MANAGER");

        ResponseEntity<String> response = patch("/api/v1/admin/accounts/" + manager.id() + "/role",
                login(admin.email(), PASSWORD), new RoleRequest("CUSTOMER_SUPPORT"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = onlyRow(admin, "admin_account.change_role");
        assertThat(row.get("resource_type")).isEqualTo("admin_account");
        assertThat(row.get("resource_id")).isEqualTo(manager.id().toString());
        assertThat(summaryOf(row)).isEqualTo(JSON.readTree("{\"role\":\"CUSTOMER_SUPPORT\"}"));
        assertThat(row.get("status")).isEqualTo("success");
    }

    @Test
    void aRefusedLastSuperAdminDemotionIsRecordedAsFailed() {
        List<UUID> parked = jdbc.queryForList("SELECT id FROM app_user WHERE " + ACTIVE_SUPER_ADMIN, UUID.class);
        jdbc.update("UPDATE app_user SET admin_disabled_at = now() WHERE " + ACTIVE_SUPER_ADMIN);
        jdbc.update("INSERT INTO role_permission (role_code, permission_key)"
                + " VALUES ('OPERATION_MANAGER', 'system.admin_account.manage') ON CONFLICT DO NOTHING");
        try {
            UserView soleAdmin = seedAdmin("audit-last-admin");
            UserView manager = seedUser(soleAdmin, "audit-last-manager", "OPERATION_MANAGER");

            ResponseEntity<String> response = patch("/api/v1/admin/accounts/" + soleAdmin.id() + "/role",
                    login(manager.email(), PASSWORD), new RoleRequest("OPERATION_MANAGER"), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            Map<String, Object> row = onlyRow(manager, "admin_account.change_role");
            assertThat(row.get("status")).isEqualTo("failed");
            assertThat(row.get("error_code")).isEqualTo("user.lastSuperAdmin");
            assertThat(row.get("resource_id")).isEqualTo(soleAdmin.id().toString());
        } finally {
            jdbc.update("DELETE FROM role_permission WHERE role_code = 'OPERATION_MANAGER'"
                    + " AND permission_key = 'system.admin_account.manage'");
            if (!parked.isEmpty()) {
                namedJdbc.update("UPDATE app_user SET admin_disabled_at = NULL, admin_disabled_by = NULL"
                        + " WHERE id IN (:ids)", new MapSqlParameterSource("ids", parked));
            }
        }
    }

    @Test
    void aRoleChangeRefusedInsideTheHandlerIsRecordedAsForbidden() {
        UserView admin = seedAdmin("audit-handler-refusal");
        UserView staff = seedUser(admin, "audit-handler-staff", "CUSTOMER_SUPPORT");
        UserView member = seedUser(admin, "audit-handler-target", "MEMBER");
        jdbc.update("INSERT INTO role_permission (role_code, permission_key)"
                + " VALUES ('CUSTOMER_SUPPORT', 'user.account.update') ON CONFLICT DO NOTHING");
        try {
            ResponseEntity<String> response = put("/api/v1/users/" + member.id(), login(staff.email(), PASSWORD),
                    new UpdateUserRequestView(null, null, "CREATOR", null), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            Map<String, Object> row = onlyRow(staff, "user.update");
            assertThat(row.get("status")).isEqualTo("failed");
            assertThat(row.get("error_code")).isEqualTo("forbidden");
            assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, member.id()))
                    .isEqualTo("MEMBER");
        } finally {
            jdbc.update("DELETE FROM role_permission WHERE role_code = 'CUSTOMER_SUPPORT'"
                    + " AND permission_key = 'user.account.update'");
        }
    }

    @Test
    void anAdminWorkflowStepUpdateRecordsTheStepAndTheWorkflow() {
        UserView admin = seedAdmin("audit-workflow-step");
        String token = login(admin.email(), PASSWORD);
        ResponseEntity<IdView> workflow = post("/api/v1/admin/workflows", token,
                new WorkflowRequest("Quy trình kiểm toán", false), IdView.class);
        assertThat(workflow.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID workflowId = workflow.getBody().id();
        ResponseEntity<IdView> step = post("/api/v1/admin/workflows/" + workflowId + "/steps", token,
                new StepRequest("echo"), IdView.class);
        assertThat(step.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID stepId = step.getBody().id();

        ResponseEntity<String> response = patch("/api/v1/admin/workflows/" + workflowId + "/steps/" + stepId,
                token, new StepEditRequest("Bước đã đổi tên"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = onlyRow(admin, "workflow_step.update");
        assertThat(row.get("resource_type")).isEqualTo("workflow_step");
        assertThat(row.get("resource_id")).isEqualTo(stepId.toString());
        assertThat(summaryOf(row)).isEqualTo(JSON.readTree("{\"id\":\"" + workflowId + "\"}"));
        assertThat(onlyRow(admin, "workflow.create").get("resource_id")).isEqualTo(workflowId.toString());
        Map<String, Object> created = onlyRow(admin, "workflow_step.create");
        assertThat(created.get("resource_id")).isEqualTo(stepId.toString());
        assertThat(summaryOf(created))
                .isEqualTo(JSON.readTree("{\"type\":\"echo\",\"id\":\"" + workflowId + "\"}"));
    }
}
