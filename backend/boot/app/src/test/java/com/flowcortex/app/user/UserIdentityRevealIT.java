package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.flowcortex.user.api.UserView;
import com.flowcortex.user.pii.PiiMask;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Masked member identity on the users routes and the audited reveal, over real HTTP. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class UserIdentityRevealIT extends AbstractAuditSpyIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String LAN_NAME = "Lê Thị Lan";
    private static final String LAN_MASKED_NAME = "L*** T*** L***";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbc;

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private UserView seedLan(UserView admin) {
        return userService.createUser(admin.id(), uniqueEmail("lan.le"), PASSWORD, LAN_NAME, "MEMBER");
    }

    private String token(UserView user) {
        return login(user.email(), PASSWORD);
    }

    private List<Map<String, Object>> rows(String action, UserView about) {
        return jdbc.queryForList("SELECT * FROM admin_audit_event WHERE action = ? AND resource_id = ?"
                + " ORDER BY occurred_at", action, about.id().toString());
    }

    @Test
    void usersList() {
        UserView admin = seedAdmin("reveal-list");
        UserView lan = seedLan(admin);

        ResponseEntity<String> response = get("/api/v1/users?q=" + lan.email(), token(admin), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = JSON.readTree(response.getBody()).get("items");
        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.get("id").asString()).isEqualTo(lan.id().toString());
        assertThat(item.get("name").asString()).isEqualTo(LAN_MASKED_NAME);
        assertThat(item.get("email").asString()).isEqualTo(PiiMask.email(lan.email())).isNotEqualTo(lan.email());
        assertThat(item.get("role").asString()).isEqualTo("MEMBER");
        assertThat(item.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(response.getBody()).doesNotContain(LAN_NAME).doesNotContain(lan.email());
    }

    @Test
    void searchStillFindsTheMember() {
        UserView admin = seedAdmin("reveal-search");
        UserView lan = seedLan(admin);
        String fragment = lan.email().substring(0, lan.email().indexOf('@'));

        ResponseEntity<String> response = get("/api/v1/users?q=" + fragment, token(admin), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = JSON.readTree(response.getBody()).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("id").asString()).isEqualTo(lan.id().toString());
        assertThat(items.get(0).get("email").asString()).isEqualTo(PiiMask.email(lan.email()));
    }

    @Test
    void updateResponse() {
        UserView admin = seedAdmin("reveal-update");
        UserView lan = seedLan(admin);

        ResponseEntity<String> response = put("/api/v1/users/" + lan.id(), token(admin),
                new UpdateUserRequestView(null, null, "CREATOR", null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("role").asString()).isEqualTo("CREATOR");
        assertThat(body.get("name").asString()).isEqualTo(LAN_MASKED_NAME);
        assertThat(body.get("email").asString()).isEqualTo(PiiMask.email(lan.email()));
    }

    @Test
    void newAuditRowAboutAMember() {
        UserView admin = seedAdmin("reveal-label");
        UserView lan = seedLan(admin);

        ResponseEntity<String> response = put("/api/v1/users/" + lan.id(), token(admin),
                new UpdateUserRequestView(null, null, "CREATOR", null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = rows("user.update", lan);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("resource_label")).isEqualTo(LAN_MASKED_NAME);
    }

    @Test
    void revealByAHolder() {
        UserView admin = seedAdmin("reveal-holder");
        UserView support = userService.createUser(admin.id(), uniqueEmail("reveal-cs"), PASSWORD, "Support",
                "CUSTOMER_SUPPORT");
        UserView lan = seedLan(admin);

        ResponseEntity<String> response = get("/api/v1/users/" + lan.id() + "/identity", token(support),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("id").asString()).isEqualTo(lan.id().toString());
        assertThat(body.get("email").asString()).isEqualTo(lan.email());
        assertThat(body.get("name").asString()).isEqualTo(LAN_NAME);
        List<Map<String, Object>> rows = rows("user.reveal_identity", lan);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("actor_user_id")).isEqualTo(support.id());
        assertThat(row.get("resource_type")).isEqualTo("user");
        assertThat(row.get("resource_label")).isEqualTo(LAN_MASKED_NAME);
        assertThat(row.get("status")).isEqualTo("success");
        assertThat(row.get("ip")).isNotNull();
    }

    @Test
    void callerWithoutTheKey() {
        UserView admin = seedAdmin("reveal-forbidden");
        UserView operator = userService.createUser(admin.id(), uniqueEmail("reveal-po"), PASSWORD, "Operator",
                "PLATFORM_OPERATOR");
        UserView lan = seedLan(admin);

        ResponseEntity<String> response = get("/api/v1/users/" + lan.id() + "/identity", token(operator),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).doesNotContain(lan.email()).doesNotContain(LAN_NAME);
        assertThat(rows("user.reveal_identity", lan)).isEmpty();
    }

    @Test
    void recordingFails() {
        UserView admin = seedAdmin("reveal-503");
        UserView lan = seedLan(admin);
        doThrow(new IllegalStateException("insert failed")).when(writer).writeOrThrow(any());

        ResponseEntity<String> response = get("/api/v1/users/" + lan.id() + "/identity", token(admin),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody()).contains("audit trail could not record")
                .doesNotContain(lan.email()).doesNotContain(LAN_NAME);
        assertThat(rows("user.reveal_identity", lan)).isEmpty();
    }

    @Test
    void everyRevealIsRecorded() {
        UserView admin = seedAdmin("reveal-twice");
        UserView lan = seedLan(admin);
        String token = token(admin);

        assertThat(get("/api/v1/users/" + lan.id() + "/identity", token, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/users/" + lan.id() + "/identity", token, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(rows("user.reveal_identity", lan)).hasSize(2);
    }

    @Test
    void revealOutsideScopeIs404() {
        UserView adminA = seedAdmin("reveal-scope-a");
        UserView adminB = seedAdmin("reveal-scope-b");
        UserView lanInB = seedLan(adminB);
        assertThat(get("/api/v1/users/" + lanInB.id() + "/identity", token(adminB), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = get("/api/v1/users/" + lanInB.id() + "/identity", token(adminA),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody()).doesNotContain(lanInB.email()).doesNotContain(LAN_NAME);
        List<Map<String, Object>> rows = rows("user.reveal_identity", lanInB);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("actor_user_id")).isEqualTo(adminB.id());
    }
}
