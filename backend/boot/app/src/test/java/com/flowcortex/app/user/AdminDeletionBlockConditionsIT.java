package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The blocking-condition console API over HTTP against Postgres; every edited row is put back after each case. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminDeletionBlockConditionsIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String BASE = "/api/v1/admin/account-deletion/block-conditions";
    private static final String RUN = "workflow.active_run";
    private static final String REQUEST = "support.open_request";
    private static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");

    record Condition(String key, boolean enabled, int sortOrder, String messageVi, String messageEn,
            String updatedBy, Instant updatedAt) {
    }

    record Update(Boolean enabled, Integer sortOrder, String messageVi, String messageEn) {
    }

    record Reason(String key, String message) {
    }

    record Eligibility(boolean eligible, List<Reason> reasons) {
    }

    record Problem(int status, URI type, String detail) {
    }

    record CreateRole(String name, String description, List<String> permissionKeys) {
    }

    record RoleView(String code) {
    }

    @Autowired
    private JdbcTemplate jdbc;

    private List<Map<String, Object>> snapshot;
    private UserView owner;
    private String ownerToken;
    private String operatorToken;
    private UserView operator;
    private String viewerRole;

    @BeforeEach
    void seed() {
        snapshot = jdbc.queryForList("SELECT * FROM deletion_block_condition");
        owner = userService.createInitialAdmin(uniqueEmail("deletion-owner"), PASSWORD, "Owner");
        ownerToken = login(owner.email(), PASSWORD);
        operator = userService.createUser(owner.id(), uniqueEmail("deletion-operator"), PASSWORD, "Operator",
                "PLATFORM_OPERATOR");
        operatorToken = login(operator.email(), PASSWORD);
    }

    @AfterEach
    void restore() {
        jdbc.update("DELETE FROM deletion_block_condition");
        for (Map<String, Object> row : snapshot) {
            jdbc.update("INSERT INTO deletion_block_condition (key, enabled, sort_order, message_vi, message_en, "
                    + "updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)", row.get("key"), row.get("enabled"),
                    row.get("sort_order"), row.get("message_vi"), row.get("message_en"), row.get("updated_by"),
                    row.get("updated_at"));
        }
        if (viewerRole != null) {
            jdbc.update("DELETE FROM app_user WHERE role = ?", viewerRole);
            jdbc.update("DELETE FROM role WHERE code = ?", viewerRole);
        }
    }

    @Test
    void listsBothConditionsInOrder() {
        ResponseEntity<Condition[]> response = get(BASE, operatorToken, Condition[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(Condition::key).containsExactly(RUN, REQUEST);
        Condition run = response.getBody()[0];
        assertThat(run.enabled()).isTrue();
        assertThat(run.sortOrder()).isZero();
        assertThat(run.messageEn()).startsWith("A workflow run is still in progress");
        assertThat(run.messageVi()).startsWith("Bạn có workflow đang chạy");
        assertThat(run.updatedBy()).isNull();
    }

    @Test
    void aMissingRowIsSeededFromTheRegisteredDefaults() {
        jdbc.update("DELETE FROM deletion_block_condition WHERE key = ?", REQUEST);

        ResponseEntity<Condition[]> response = get(BASE, operatorToken, Condition[].class);

        assertThat(response.getBody()).extracting(Condition::key).containsExactly(RUN, REQUEST);
        assertThat(response.getBody()[1].enabled()).isTrue();
    }

    @Test
    void operatorDisablesACondition() {
        UserView member = userService.createUser(owner.id(), uniqueEmail("deletion-member"), PASSWORD, "Member",
                "MEMBER");
        String memberToken = login(member.email(), PASSWORD);
        openRequest(member.id());
        assertThat(get("/api/v1/me/account-deletion/eligibility", memberToken, Eligibility.class).getBody()
                .reasons()).extracting(Reason::key).containsExactly(REQUEST);

        ResponseEntity<Condition> response = put(BASE + "/" + REQUEST, operatorToken,
                new Update(false, 1, "  Đã tắt  ", "  Switched off  "), Condition.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Condition row = response.getBody();
        assertThat(row.key()).isEqualTo(REQUEST);
        assertThat(row.enabled()).isFalse();
        assertThat(row.messageVi()).isEqualTo("Đã tắt");
        assertThat(row.messageEn()).isEqualTo("Switched off");
        assertThat(row.updatedBy()).isEqualTo(operator.email());
        assertThat(row.updatedAt()).isNotNull();
        Eligibility eligibility = get("/api/v1/me/account-deletion/eligibility", memberToken, Eligibility.class)
                .getBody();
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.reasons()).isEmpty();
    }

    @Test
    void updateBeforeAnyEvaluationStillAnswersTheRow() {
        jdbc.update("DELETE FROM deletion_block_condition");

        ResponseEntity<Condition> response = put(BASE + "/" + RUN, operatorToken,
                new Update(true, 5, "Chạy dở", "Run pending"), Condition.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().sortOrder()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deletion_block_condition", Integer.class)).isEqualTo(2);
    }

    @Test
    void unknownKeyIs404AndInsertsNothing() {
        int before = jdbc.queryForObject("SELECT count(*) FROM deletion_block_condition", Integer.class);

        ResponseEntity<Problem> response = put(BASE + "/billing.pending_payment", operatorToken,
                new Update(true, 2, "vi", "en"), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("not-found"));
        assertThat(response.getBody().detail()).isEqualTo("No deletion condition billing.pending_payment.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deletion_block_condition", Integer.class))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deletion_block_condition WHERE key = ?", Integer.class,
                "billing.pending_payment")).isZero();
    }

    @Test
    void blankMessageIs400() {
        Condition before = stored(REQUEST);

        for (Update body : List.of(
                new Update(true, 1, "vi", "   "),
                new Update(true, 1, "", "en"),
                new Update(true, 1, null, "en"))) {
            ResponseEntity<Problem> response = put(BASE + "/" + REQUEST, operatorToken, body, Problem.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
        }
        assertThat(stored(REQUEST)).isEqualTo(before);
    }

    @Test
    void sortOrderOver999Is400() {
        Condition before = stored(REQUEST);

        for (Integer order : new Integer[] {1000, -1, null}) {
            ResponseEntity<Problem> response = put(BASE + "/" + REQUEST, operatorToken,
                    new Update(true, order, "vi", "en"), Problem.class);

            assertThat(response.getStatusCode()).as(String.valueOf(order)).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
        }
        assertThat(stored(REQUEST)).isEqualTo(before);
    }

    @Test
    void aMessageOver500CharactersIs400AndExactly500IsStored() {
        ResponseEntity<Problem> tooLong = put(BASE + "/" + REQUEST, operatorToken,
                new Update(true, 1, "vi", "x".repeat(501)), Problem.class);
        ResponseEntity<Condition> limit = put(BASE + "/" + REQUEST, operatorToken,
                new Update(true, 1, "vi", "x".repeat(500) + "  "), Condition.class);

        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(limit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(limit.getBody().messageEn()).hasSize(500);
    }

    @Test
    void anOmittedSwitchIs400() {
        ResponseEntity<Problem> response = put(BASE + "/" + REQUEST, operatorToken,
                new Update(null, 1, "vi", "en"), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void viewerWithoutUpdateGets403OnPut() {
        String viewerToken = viewerToken();

        ResponseEntity<Condition[]> list = get(BASE, viewerToken, Condition[].class);
        ResponseEntity<Problem> update = put(BASE + "/" + REQUEST, viewerToken, new Update(false, 1, "vi", "en"),
                Problem.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(stored(REQUEST).enabled()).isTrue();
    }

    @Test
    void customerSupportGets403OnList() {
        UserView support = userService.createUser(owner.id(), uniqueEmail("deletion-support"), PASSWORD, "Support",
                "CUSTOMER_SUPPORT");

        ResponseEntity<Problem> response = get(BASE, login(support.email(), PASSWORD), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(BASE, null, Problem.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aMemberCannotReachTheConsoleRoutes() {
        UserView member = userService.createUser(owner.id(), uniqueEmail("deletion-plain"), PASSWORD, "Member",
                "MEMBER");

        assertThat(get(BASE, login(member.email(), PASSWORD), Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String viewerToken() {
        ResponseEntity<RoleView> created = post("/api/v1/admin/roles", ownerToken,
                new CreateRole("Deletion viewer " + UUID.randomUUID().toString().substring(0, 8), null,
                        List.of("platform.setting.view")),
                RoleView.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        viewerRole = created.getBody().code();
        UserView viewer = userService.createUser(owner.id(), uniqueEmail("deletion-viewer"), PASSWORD, "Viewer",
                viewerRole);
        return login(viewer.email(), PASSWORD);
    }

    private Condition stored(String key) {
        return jdbc.queryForObject("SELECT key, enabled, sort_order, message_vi, message_en, updated_at "
                + "FROM deletion_block_condition WHERE key = ?",
                (row, i) -> new Condition(row.getString("key"), row.getBoolean("enabled"), row.getInt("sort_order"),
                        row.getString("message_vi"), row.getString("message_en"), null,
                        row.getTimestamp("updated_at").toInstant()),
                key);
    }

    private void openRequest(UUID requester) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO support_request (id, requester_id, type, subject, status, member_unread, "
                + "last_activity_at, created_at, updated_at) VALUES (?, ?, 'other', 'Deletion fixture', 'new', "
                + "false, ?, ?, ?)", UUID.randomUUID(), requester, now, now, now);
    }
}
