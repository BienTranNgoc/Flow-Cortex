package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.ActivityCursor;
import com.flowcortex.user.api.UserActivitySource;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.application.AuthRetentionJob;
import com.flowcortex.user.pii.PiiMask;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A member's activity timeline over real HTTP and rows written straight into each source's table. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class UserActivityIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> OWNER_TIME_INDEX = Map.of(
            "admin_action", "idx_admin_audit_event_member_page",
            "auth", "idx_auth_event_user_id_occurred_at",
            "workflow", "idx_workflow_owner_created_page",
            "workflow_run", "idx_workflow_run_owner_started_page",
            "support_request", "idx_support_request_requester_created_page");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthRetentionJob retentionJob;

    @Autowired
    private List<UserActivitySource> sources;

    record ItemView(UUID id, String source, String action, String actorType, String actorLabel, String objectType,
            String objectId, String objectLabel, String status, Instant occurredAt) {
    }

    record PageView(List<ItemView> items, String nextCursor) {
    }

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private UserView seedUser(UserView admin, String label, String role) {
        return userService.createUser(admin.id(), uniqueEmail(label), PASSWORD, label, role);
    }

    private static Instant base() {
        return Instant.now().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);
    }

    private ResponseEntity<PageView> activity(String token, UUID id, String query) {
        return get("/api/v1/users/" + id + "/activity" + query, token, PageView.class);
    }

    private PageView firstPage(UserView admin, UserView member) {
        ResponseEntity<PageView> response = activity(login(admin.email(), PASSWORD), member.id(), "");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private UUID workflow(UUID owner, String name, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at) "
                + "VALUES (?, ?, ?, false, ?, ?)", id, owner, name, Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return id;
    }

    private UUID run(UUID workflowId, UUID owner, String status, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at) VALUES (?, ?, ?, ?, ?)",
                id, workflowId, owner, status, Timestamp.from(startedAt));
        return id;
    }

    private UUID supportRequest(UUID requester, String subject, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO support_request (id, requester_id, type, subject, status, member_unread, "
                + "last_activity_at, created_at, updated_at) VALUES (?, ?, 'other', ?, 'new', false, ?, ?, ?)",
                id, requester, subject, Timestamp.from(createdAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return id;
    }

    private UUID authEvent(UUID user, String type, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_event (id, user_id, type, occurred_at) VALUES (?, ?, ?, ?)", id, user, type,
                Timestamp.from(at));
        return id;
    }

    private UUID auditRow(UUID subject, String resourceType, String label, String actorName, String actorEmail,
            String action, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_audit_event (id, actor_user_id, actor_email, actor_name, action, "
                + "resource_type, resource_id, resource_label, status, occurred_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'success', ?)", id, UUID.randomUUID(), actorEmail, actorName,
                action, resourceType, subject.toString(), label, Timestamp.from(at));
        return id;
    }

    private static long number(JdbcTemplate jdbc, UUID requestId) {
        return jdbc.queryForObject("SELECT number FROM support_request WHERE id = ?", Long.class, requestId);
    }

    @Test
    void oneMemberAcrossSources() {
        UserView admin = seedAdmin("activity-admin");
        UserView lan = seedUser(admin, "activity-lan", "MEMBER");
        Instant day = base();
        authEvent(lan.id(), "LOGIN_SUCCESS", day.plus(Duration.ofHours(8)));
        UUID workflowId = workflow(lan.id(), "Video sản phẩm", day.plus(Duration.ofHours(8)).plusSeconds(600));
        run(workflowId, lan.id(), "completed", day.plus(Duration.ofHours(8)).plusSeconds(1200));
        supportRequest(lan.id(), "Không xuất được video", day.plus(Duration.ofHours(8)).plusSeconds(1800));
        auditRow(lan.id(), "user", lan.email(), "Mai Tran", "mai@example.com", "user.update",
                day.plus(Duration.ofHours(9)));

        PageView page = firstPage(admin, lan);

        assertThat(page.items()).extracting(ItemView::source)
                .containsExactly("admin_action", "support_request", "workflow_run", "workflow", "auth");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void anotherMembersItemsAreAbsent() {
        UserView admin = seedAdmin("activity-others-admin");
        UserView lan = seedUser(admin, "activity-others-lan", "MEMBER");
        UserView minh = seedUser(admin, "activity-others-minh", "MEMBER");
        UUID lanWorkflow = workflow(lan.id(), "Của Lan", base());
        UUID lanRun = run(lanWorkflow, lan.id(), "completed", base().plusSeconds(60));
        UUID minhWorkflow = workflow(minh.id(), "Của Minh", base());
        UUID minhRun = run(minhWorkflow, minh.id(), "completed", base().plusSeconds(120));

        PageView page = firstPage(admin, lan);

        assertThat(page.items()).extracting(ItemView::id).containsExactly(lanRun, lanWorkflow)
                .doesNotContain(minhRun, minhWorkflow);
    }

    @Test
    void accountOutsideTheCallersScope() {
        UserView admin = seedAdmin("activity-scope-admin");
        UserView otherAdmin = seedAdmin("activity-scope-other-admin");
        UserView foreign = seedUser(otherAdmin, "activity-scope-foreign", "MEMBER");
        workflow(foreign.id(), "Không được thấy", base());

        ResponseEntity<String> response =
                get("/api/v1/users/" + foreign.id() + "/activity", login(admin.email(), PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).doesNotContain("Không được thấy");
    }

    @Test
    void callerWithoutTheKey() {
        UserView admin = seedAdmin("activity-key-admin");
        UserView editor = seedUser(admin, "activity-key-editor", "CONTENT_EDITOR");
        UserView lan = seedUser(admin, "activity-key-lan", "MEMBER");

        ResponseEntity<String> response =
                get("/api/v1/users/" + lan.id() + "/activity", login(editor.email(), PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void signInOlderThanAuthRetention() {
        UserView admin = seedAdmin("activity-retention-admin");
        UserView lan = seedUser(admin, "activity-retention-lan", "MEMBER");
        UUID old = authEvent(lan.id(), "LOGIN_SUCCESS", Instant.now().minus(Duration.ofDays(100)));
        UUID recent = authEvent(lan.id(), "LOGIN_SUCCESS", Instant.now().minus(Duration.ofDays(1)));

        assertThat(firstPage(admin, lan).items()).extracting(ItemView::id).containsExactly(recent);

        retentionJob.purge();

        assertThat(firstPage(admin, lan).items()).extracting(ItemView::id).containsExactly(recent);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE id = ?", Long.class, old)).isZero();
    }

    @Test
    void adminActionItem() {
        UserView admin = seedAdmin("activity-action-admin");
        UserView lan = seedUser(admin, "activity-action-lan", "MEMBER");
        UUID named = auditRow(lan.id(), "user", lan.email(), "Mai Tran", "mai@example.com", "user.update",
                base().plusSeconds(60));
        UUID unnamed = auditRow(lan.id(), "user", lan.name(), null, "an@example.com", "user.update",
                base().plusSeconds(30));
        UUID staff = auditRow(lan.id(), "admin_account", "Platform Ops", "Mai Tran", "mai@example.com",
                "admin_account.change_role", base());
        auditRow(UUID.randomUUID(), "user", "someone@else.vn", "Mai Tran", "mai@example.com", "user.update",
                base().plusSeconds(90));

        List<ItemView> items = firstPage(admin, lan).items();

        assertThat(items).extracting(ItemView::id).containsExactly(named, unnamed, staff);
        ItemView first = items.get(0);
        assertThat(first.source()).isEqualTo("admin_action");
        assertThat(first.action()).isEqualTo("user.update");
        assertThat(first.actorType()).isEqualTo("admin");
        assertThat(first.actorLabel()).isEqualTo("Mai Tran");
        assertThat(first.objectType()).isEqualTo("user");
        assertThat(first.objectId()).isEqualTo(lan.id().toString());
        assertThat(first.objectLabel()).isEqualTo(PiiMask.email(lan.email())).isNotEqualTo(lan.email());
        assertThat(first.status()).isEqualTo("success");
        assertThat(items.get(1).actorLabel()).isEqualTo("an@example.com");
        assertThat(items.get(1).objectLabel()).isEqualTo(PiiMask.name(lan.name()));
        assertThat(items.get(2).objectType()).isEqualTo("admin_account");
        assertThat(items.get(2).objectLabel()).isEqualTo("Platform Ops");
    }

    @Test
    void signInItem() {
        UserView admin = seedAdmin("activity-signin-admin");
        UserView lan = seedUser(admin, "activity-signin-lan", "MEMBER");
        UUID event = authEvent(lan.id(), "LOGIN_SUCCESS", base());

        ItemView item = firstPage(admin, lan).items().get(0);

        assertThat(item.id()).isEqualTo(event);
        assertThat(item.source()).isEqualTo("auth");
        assertThat(item.action()).isEqualTo("auth.login_success");
        assertThat(item.actorType()).isEqualTo("user");
        assertThat(item.actorLabel()).isNull();
    }

    @Test
    void workflowItem() {
        UserView admin = seedAdmin("activity-workflow-admin");
        UserView lan = seedUser(admin, "activity-workflow-lan", "MEMBER");
        UUID workflowId = workflow(lan.id(), "Video sản phẩm", base());
        jdbc.update("UPDATE workflow SET deleted_at = now() WHERE id = ?", workflowId);

        ItemView item = firstPage(admin, lan).items().get(0);

        assertThat(item.source()).isEqualTo("workflow");
        assertThat(item.action()).isEqualTo("workflow.create");
        assertThat(item.objectType()).isEqualTo("workflow");
        assertThat(item.objectId()).isEqualTo(workflowId.toString());
        assertThat(item.objectLabel()).isEqualTo("Video sản phẩm");
    }

    @Test
    void runItem() {
        UserView admin = seedAdmin("activity-run-admin");
        UserView lan = seedUser(admin, "activity-run-lan", "MEMBER");
        UUID workflowId = workflow(lan.id(), "Video sản phẩm", base());
        UUID runId = run(workflowId, lan.id(), "error", base().plusSeconds(60));
        jdbc.update("UPDATE workflow_run SET deleted_at = now() WHERE id = ?", runId);

        ItemView item = firstPage(admin, lan).items().get(0);

        assertThat(item.id()).isEqualTo(runId);
        assertThat(item.source()).isEqualTo("workflow_run");
        assertThat(item.action()).isEqualTo("workflow_run.start");
        assertThat(item.objectLabel()).isEqualTo("Video sản phẩm");
        assertThat(item.status()).isEqualTo("error");
    }

    @Test
    void supportRequestItem() {
        UserView admin = seedAdmin("activity-support-admin");
        UserView lan = seedUser(admin, "activity-support-lan", "MEMBER");
        UUID requestId = supportRequest(lan.id(), "Không xuất được video", base());

        ItemView item = firstPage(admin, lan).items().get(0);

        assertThat(item.source()).isEqualTo("support_request");
        assertThat(item.action()).isEqualTo("support_request.create");
        assertThat(item.objectLabel()).isEqualTo("#" + number(jdbc, requestId) + " Không xuất được video");
    }

    @Test
    void tiesAcrossSourcesAtAPageBoundary() {
        UserView admin = seedAdmin("activity-ties-admin");
        UserView lan = seedUser(admin, "activity-ties-lan", "MEMBER");
        String token = login(admin.email(), PASSWORD);
        Instant shared = base().plusNanos(123_456_000);
        UUID workflowId = workflow(lan.id(), "Chung giờ", shared);
        UUID runId = run(workflowId, lan.id(), "completed", shared);
        UUID older = authEvent(lan.id(), "LOGIN_SUCCESS", shared.minusSeconds(60));

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<PageView> response =
                    activity(token, lan.id(), "?limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().items()).hasSize(1);
            seen.add(response.getBody().items().get(0).id());
            cursor = response.getBody().nextCursor();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactly(workflowId, runId, older);
    }

    @Test
    void microsecondsKeepTheirOrder() {
        UserView admin = seedAdmin("activity-micros-admin");
        UserView lan = seedUser(admin, "activity-micros-lan", "MEMBER");
        String token = login(admin.email(), PASSWORD);
        UUID workflowId = workflow(lan.id(), "Vi giây", base().minusSeconds(60));
        Instant first = base().plusNanos(1_000);
        UUID earlier = run(workflowId, lan.id(), "completed", first);
        UUID later = run(workflowId, lan.id(), "completed", first.plusNanos(1_000));

        PageView page = activity(token, lan.id(), "?limit=1").getBody();
        PageView next = activity(token, lan.id(), "?limit=1&cursor=" + page.nextCursor()).getBody();

        assertThat(page.items()).extracting(ItemView::id).containsExactly(later);
        assertThat(next.items()).extracting(ItemView::id).containsExactly(earlier);
    }

    @Test
    void limitTooLarge() {
        UserView admin = seedAdmin("activity-limit-admin");
        UserView lan = seedUser(admin, "activity-limit-lan", "MEMBER");
        String token = login(admin.email(), PASSWORD);

        for (String limit : List.of("500", "0", "-1", "abc")) {
            ResponseEntity<String> response =
                    get("/api/v1/users/" + lan.id() + "/activity?limit=" + limit, token, String.class);
            assertThat(response.getStatusCode()).as("limit=" + limit).isEqualTo(HttpStatus.BAD_REQUEST);
            JsonNode body = JSON.readTree(response.getBody());
            assertThat(body.get("type").asString()).endsWith("/validation-failed");
            assertThat(body.get("errors").get("limit")).isNotNull();
            assertThat(body.has("items")).isFalse();
        }
    }

    @Test
    void tamperedCursor() {
        UserView admin = seedAdmin("activity-cursor-admin");
        UserView lan = seedUser(admin, "activity-cursor-lan", "MEMBER");
        String token = login(admin.email(), PASSWORD);
        String shapeOk = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1:auth:not-a-uuid".getBytes(StandardCharsets.UTF_8));

        for (String cursor : List.of("abc", "a!b", shapeOk)) {
            ResponseEntity<String> response =
                    get("/api/v1/users/" + lan.id() + "/activity?cursor=" + cursor, token, String.class);
            assertThat(response.getStatusCode()).as("cursor=" + cursor).isEqualTo(HttpStatus.BAD_REQUEST);
            JsonNode body = JSON.readTree(response.getBody());
            assertThat(body.get("type").asString()).endsWith("/validation-failed");
            assertThat(body.get("errors").get("cursor")).isNotNull();
            assertThat(body.has("items")).isFalse();
        }
    }

    @Test
    void plansOverAHeavyMember() {
        UserView admin = seedAdmin("activity-plan-admin");
        UserView heavy = seedUser(admin, "activity-plan-heavy", "MEMBER");
        UUID id = heavy.id();
        UUID anchor = workflow(id, "anchor", Instant.now().minus(Duration.ofDays(2)));
        UUID bulkActor = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at) "
                    + "SELECT gen_random_uuid(), ?, 'bulk ' || g, false, now() - g * interval '1 minute', now() "
                    + "FROM generate_series(1, 500) g", id);
            jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at) "
                    + "SELECT gen_random_uuid(), ?, ?, 'completed', now() - g * interval '1 second' "
                    + "FROM generate_series(1, 5000) g", anchor, id);
            jdbc.update("INSERT INTO support_request (id, requester_id, type, subject, status, member_unread, "
                    + "last_activity_at, created_at, updated_at) SELECT gen_random_uuid(), ?, 'other', "
                    + "'activity plan request', 'new', false, now(), now() - g * interval '1 minute', now() "
                    + "FROM generate_series(1, 500) g", id);
            jdbc.update("INSERT INTO admin_audit_event (id, actor_user_id, action, resource_type, resource_id, "
                    + "status, occurred_at) SELECT gen_random_uuid(), ?, 'user.update', 'user', ?, 'success', "
                    + "now() - g * interval '1 minute' FROM generate_series(1, 500) g", bulkActor, id.toString());
            jdbc.update("INSERT INTO auth_event (id, user_id, type, occurred_at) SELECT gen_random_uuid(), ?, "
                    + "'LOGIN_SUCCESS', now() - g * interval '1 minute' FROM generate_series(1, 500) g", id);
            addOtherMembersRows(anchor, bulkActor);
            for (String table : List.of("workflow", "workflow_run", "support_request", "admin_audit_event",
                    "auth_event")) {
                jdbc.execute("ANALYZE " + table);
            }
            assertEveryPlanReadsTheOwnerIndex(id);
        } finally {
            removeBulkRows(id, anchor, bulkActor);
        }
    }

    private void assertEveryPlanReadsTheOwnerIndex(UUID id) {
        Instant recent = Instant.now().minus(Duration.ofMinutes(5));
        List<ActivityCursor> positions = Arrays.asList(null,
                new ActivityCursor(recent, "workflow_run", UUID.randomUUID()),
                new ActivityCursor(recent, "admin_action", UUID.randomUUID()));
        for (UserActivitySource source : sources) {
            for (ActivityCursor position : positions) {
                String label = source.source() + " after " + position;
                List<JsonNode> plans = explain(source, id, position);
                assertThat(plans).as(label).hasSize(1);
                List<JsonNode> nodes = new ArrayList<>();
                collect(plans.get(0), nodes);
                assertThat(nodes).as(label + " reads the owner and time index")
                        .anyMatch(node -> OWNER_TIME_INDEX.get(source.source())
                                .equals(node.path("Index Name").asString()));
                assertThat(nodes).as(label + " has no sort over the member's rows")
                        .noneMatch(node -> "Sort".equals(node.get("Node Type").asString()));
                assertThat(nodes).filteredOn(node -> "Incremental Sort".equals(node.get("Node Type").asString()))
                        .allSatisfy(node -> assertThat(node.get("Presorted Key").toString()).contains("occurred_at"));
            }
        }
    }

    /** Other tests count rows of these tables, so the bulk rows leave with the test. */
    private void removeBulkRows(UUID member, UUID anchorWorkflow, UUID bulkActor) {
        jdbc.update("DELETE FROM workflow_run WHERE workflow_id = ?", anchorWorkflow);
        jdbc.update("DELETE FROM workflow WHERE owner_id = ? OR name = 'activity plan noise'", member);
        jdbc.update("DELETE FROM support_request WHERE subject IN ('activity plan request', 'activity plan noise')");
        jdbc.update("DELETE FROM admin_audit_event WHERE actor_user_id = ?", bulkActor);
        jdbc.update("DELETE FROM auth_event WHERE user_id = ? OR user_id::text LIKE '00000000-0000-0000-0000-%'",
                member);
    }

    /** Enough rows of other owners that an index on time alone is a worse plan than the owner's index. */
    private void addOtherMembersRows(UUID anchorWorkflow, UUID bulkActor) {
        String owner = "('00000000-0000-0000-0000-' || lpad((g % 2000)::text, 12, '0'))::uuid";
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at) "
                + "SELECT gen_random_uuid(), " + owner + ", 'activity plan noise', false, "
                + "now() - g * interval '1 second', now() FROM generate_series(1, 20000) g");
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at) "
                + "SELECT gen_random_uuid(), ?, " + owner + ", 'completed', now() - g * interval '1 second' "
                + "FROM generate_series(1, 40000) g", anchorWorkflow);
        jdbc.update("INSERT INTO support_request (id, requester_id, type, subject, status, member_unread, "
                + "last_activity_at, created_at, updated_at) SELECT gen_random_uuid(), " + owner
                + ", 'other', 'activity plan noise', 'new', false, now(), now() - g * interval '1 second', now() "
                + "FROM generate_series(1, 20000) g");
        jdbc.update("INSERT INTO admin_audit_event (id, actor_user_id, action, resource_type, resource_id, status, "
                + "occurred_at) SELECT gen_random_uuid(), ?, 'user.update', 'user', " + owner
                + "::text, 'success', now() - g * interval '1 second' FROM generate_series(1, 20000) g", bulkActor);
        jdbc.update("INSERT INTO auth_event (id, user_id, type, occurred_at) SELECT gen_random_uuid(), " + owner
                + ", 'LOGIN_SUCCESS', now() - g * interval '1 second' FROM generate_series(1, 20000) g");
    }

    private List<JsonNode> explain(UserActivitySource source, UUID account, ActivityCursor after) {
        NamedParameterJdbcTemplate real = (NamedParameterJdbcTemplate) ReflectionTestUtils.getField(source, "jdbc");
        ExplainingTemplate explaining = new ExplainingTemplate(real);
        ReflectionTestUtils.setField(source, "jdbc", explaining);
        try {
            source.page(account, after, 51);
        } finally {
            ReflectionTestUtils.setField(source, "jdbc", real);
        }
        return explaining.plans;
    }

    private static void collect(JsonNode node, List<JsonNode> into) {
        if (node.isObject() && node.has("Node Type")) {
            into.add(node);
        }
        for (JsonNode child : node) {
            collect(child, into);
        }
    }

    /** Runs {@code EXPLAIN} over the very statement and parameters a source sends instead of the query itself. */
    private static final class ExplainingTemplate extends NamedParameterJdbcTemplate {

        private final NamedParameterJdbcTemplate real;
        private final List<JsonNode> plans = new ArrayList<>();

        ExplainingTemplate(NamedParameterJdbcTemplate real) {
            super(real.getJdbcOperations());
            this.real = real;
        }

        @Override
        public <T> List<T> query(String sql, SqlParameterSource params, RowMapper<T> mapper) {
            plans.add(JSON.readTree(real.queryForObject("EXPLAIN (FORMAT JSON) " + sql, params, String.class)));
            return List.of();
        }

        @Override
        public <T> List<T> query(String sql, Map<String, ?> params, RowMapper<T> mapper) {
            throw new UnsupportedOperationException("sources bind through a SqlParameterSource");
        }
    }
}
