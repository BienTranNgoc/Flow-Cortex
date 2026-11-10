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
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** One audit row per audited admin write over real HTTP, none for a request refused before its handler. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.support.enabled=true")
@AutoConfigureTestRestTemplate
class AdminAuditRecordingIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String KB = "/api/v1/admin/support/kb";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbc;

    record GroupRequest(String name) {
    }

    record ArticleRequest(UUID groupId, String slug, String title, String body, Integer featuredPosition) {
    }

    record IdView(UUID id, String title) {
    }

    record ReplyRequest(String body, List<UUID> uploadIds, String status) {
    }

    record StatusRequest(String status) {
    }

    record CreateRequest(String type, String subject, String description, List<UUID> uploadIds) {
    }

    record RequestView(long number) {
    }

    record SettingsUpdate(Boolean assistantEnabled) {
    }

    private UserView admin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label + " Admin");
    }

    private String tokenOf(UserView user) {
        return login(user.email(), PASSWORD);
    }

    private List<Map<String, Object>> rows(UserView actor) {
        return jdbc.queryForList("SELECT *, change_summary::text AS summary_text FROM admin_audit_event"
                + " WHERE actor_user_id = ? ORDER BY occurred_at", actor.id());
    }

    private Map<String, Object> onlyRow(UserView actor) {
        List<Map<String, Object>> rows = rows(actor);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private UUID createGroup(String token, String name) {
        ResponseEntity<IdView> response = post(KB + "/groups", token, new GroupRequest(name), IdView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private IdView createArticle(String token, UUID group, String title) {
        ResponseEntity<IdView> response = post(KB + "/articles", token,
                new ArticleRequest(group, "slug-" + UUID.randomUUID().toString().substring(0, 8), title, "Nội dung", null),
                IdView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private long fileRequestAsMember(UserView admin) {
        UserView member = userService.createUser(admin.id(), uniqueEmail("member-req"), PASSWORD, "Member", "MEMBER");
        ResponseEntity<RequestView> response = post("/api/v1/support/requests", tokenOf(member),
                new CreateRequest("account", "Cần hỗ trợ", "Mô tả chi tiết vấn đề gặp phải.", List.of()),
                RequestView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().number();
    }

    @Test
    void articleCreated() {
        UserView admin = admin("article-created");
        UUID group = createGroup(tokenOf(admin), "Nhóm " + UUID.randomUUID());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenOf(admin));
        headers.set(HttpHeaders.USER_AGENT, "AuditIT/1.0");
        ResponseEntity<IdView> response = restTemplate.exchange(KB + "/articles", HttpMethod.POST,
                new HttpEntity<>(new ArticleRequest(group, "doi-mat-khau", "Đổi mật khẩu", "Nội dung", null), headers),
                IdView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> row = rows(admin).stream()
                .filter(r -> "support_kb_article.create".equals(r.get("action"))).findFirst().orElseThrow();

        assertThat(row.get("resource_type")).isEqualTo("support_kb_article");
        assertThat(row.get("resource_id")).isEqualTo(response.getBody().id().toString());
        assertThat(row.get("resource_label")).isEqualTo("Đổi mật khẩu");
        assertThat(row.get("status")).isEqualTo("success");
        assertThat(row.get("error_code")).isNull();
        assertThat(row.get("actor_email")).isEqualTo(admin.email());
        assertThat(row.get("actor_name")).isEqualTo("article-created Admin");
        assertThat(row.get("ip")).isNotNull();
        assertThat(row.get("user_agent")).isEqualTo("AuditIT/1.0");
        assertThat(row.get("origin_source")).isEqualTo("http");
    }

    @Test
    void actorRenamedOrDeletedLater() {
        UserView admin = admin("actor-later");
        createGroup(tokenOf(admin), "Nhóm " + UUID.randomUUID());

        jdbc.update("UPDATE app_user SET name = 'Renamed' WHERE id = ?", admin.id());

        Map<String, Object> row = onlyRow(admin);
        assertThat(row.get("actor_email")).isEqualTo(admin.email());
        assertThat(row.get("actor_name")).isEqualTo("actor-later Admin");
    }

    @Test
    void longUserAgent() {
        UserView admin = admin("long-ua");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenOf(admin));
        headers.set(HttpHeaders.USER_AGENT, "a".repeat(900));
        ResponseEntity<IdView> response = restTemplate.exchange(KB + "/groups", HttpMethod.POST,
                new HttpEntity<>(new GroupRequest("Nhóm " + UUID.randomUUID()), headers), IdView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat((String) onlyRow(admin).get("user_agent")).isEqualTo("a".repeat(512));
    }

    @Test
    void conflict() {
        UserView admin = admin("conflict");
        String token = tokenOf(admin);
        String name = "Nhóm " + UUID.randomUUID();
        createGroup(token, name);

        ResponseEntity<String> response = post(KB + "/groups", token, new GroupRequest(name), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        List<Map<String, Object>> rows = rows(admin);
        assertThat(rows).hasSize(2);
        Map<String, Object> failed = rows.get(1);
        assertThat(failed.get("action")).isEqualTo("support_kb_group.create");
        assertThat(failed.get("status")).isEqualTo("failed");
        assertThat(failed.get("error_code")).isEqualTo("support.kb.groupNameTaken");
        assertThat(failed.toString()).doesNotContain(name);
    }

    @Test
    void bodyFailsValidation() {
        UserView admin = admin("validation");

        ResponseEntity<String> response = put("/api/v1/admin/support/settings", tokenOf(admin), Map.of(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> row = onlyRow(admin);
        assertThat(row.get("action")).isEqualTo("support_setting.update");
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("error_code")).isEqualTo("validation-failed");
    }

    @Test
    void memberCallsAnAdminWrite() {
        UserView admin = admin("member-call");
        UserView member = userService.createUser(admin.id(), uniqueEmail("member"), PASSWORD, "Member", "MEMBER");

        ResponseEntity<String> response = post(KB + "/groups", tokenOf(member), new GroupRequest("Nhóm"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rows(member)).isEmpty();
    }

    @Test
    void staffWithoutTheKey() {
        UserView admin = admin("staff-owner");
        UserView staff = userService.createUser(admin.id(), uniqueEmail("staff"), PASSWORD, "Linh", "CUSTOMER_SUPPORT");

        ResponseEntity<String> response = post(KB + "/groups", tokenOf(staff), new GroupRequest("Nhóm"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rows(staff)).isEmpty();
    }

    @Test
    void idFromThePath() {
        UserView admin = admin("id-path");
        String token = tokenOf(admin);
        IdView article = createArticle(token, createGroup(token, "Nhóm " + UUID.randomUUID()), "Bài viết");
        post(KB + "/articles/" + article.id() + "/publish", token, null, String.class);

        ResponseEntity<String> response = post(KB + "/articles/" + article.id() + "/archive", token, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = rows(admin).stream()
                .filter(r -> "support_kb_article.archive".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(row.get("resource_id")).isEqualTo(article.id().toString());
        assertThat(row.get("resource_label")).isEqualTo("Bài viết");
    }

    @Test
    void nothingToName() {
        UserView admin = admin("nothing-to-name");
        String token = tokenOf(admin);
        UUID group = createGroup(token, "Nhóm " + UUID.randomUUID());

        ResponseEntity<String> response = delete(KB + "/groups/" + group, token, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> row = rows(admin).stream()
                .filter(r -> "support_kb_group.delete".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(row.get("resource_id")).isEqualTo(group.toString());
        assertThat(row.get("resource_label")).isNull();
    }

    @Test
    void supportRequestByNumber() {
        UserView admin = admin("by-number");
        long number = fileRequestAsMember(admin);

        ResponseEntity<String> response = put("/api/v1/admin/support/requests/" + number + "/status", tokenOf(admin),
                new StatusRequest("in_progress"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = onlyRow(admin);
        assertThat(row.get("action")).isEqualTo("support_request.change_status");
        assertThat(row.get("resource_id")).isEqualTo(String.valueOf(number));
    }

    @Test
    void aRepliesTextIsNeverStored() throws Exception {
        UserView admin = admin("reply-text");
        long number = fileRequestAsMember(admin);
        String text = "Mật khẩu tạm của bạn là 123456";

        ResponseEntity<String> response = post("/api/v1/admin/support/requests/" + number + "/messages",
                tokenOf(admin), new ReplyRequest(text, List.of(), "waiting_member"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> row = onlyRow(admin);
        assertThat(JSON.readTree((String) row.get("summary_text")))
                .isEqualTo(JSON.readTree("{\"status\":\"waiting_member\"}"));
        assertThat(row.values().toString()).doesNotContain(text).doesNotContain("123456");
    }

    @Test
    void settingsCaptureOnlyTheFlag() throws Exception {
        UserView admin = admin("settings");

        ResponseEntity<String> response = put("/api/v1/admin/support/settings", tokenOf(admin),
                new SettingsUpdate(false), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = onlyRow(admin);
        assertThat(JSON.readTree((String) row.get("summary_text")))
                .isEqualTo(JSON.readTree("{\"assistantEnabled\":false}"));
    }

    @Test
    void aGetWritesNoRow() {
        UserView admin = admin("get-no-row");

        ResponseEntity<String> response = get(KB + "/groups", tokenOf(admin), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rows(admin)).isEmpty();
    }
}
