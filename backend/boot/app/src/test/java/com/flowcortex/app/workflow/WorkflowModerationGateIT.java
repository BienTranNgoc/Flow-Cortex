package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.AudienceView.Audience;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowAudience;
import com.flowcortex.workflow.api.WorkflowView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

/**
 * A hidden or removed workflow is out of reach for everyone but its owner: lists, detail, run start, grants,
 * share keys and every reader that loads a workflow on behalf of a member. The status is set by SQL, since the
 * staff routes that write it are not under test here.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class WorkflowModerationGateIT extends AbstractWorkflowHttpIT {

    private static final String WORKFLOWS = "/api/v1/workflows/";

    private final List<UUID> moderated = new ArrayList<>();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkflowAudience workflowAudience;

    @Test
    void hiddenPublicWorkflowLeavesAnotherMembersList() {
        UserView owner = newUser("gate-list-owner");
        String lan = lanToken(owner, "gate-list-lan");
        UUID id = publicWorkflow(owner).id();
        assertThat(call(HttpMethod.GET, "/api/v1/workflows", lan, null, String.class).getBody()).contains(id.toString());

        moderate(id, "hidden");

        assertThat(call(HttpMethod.GET, "/api/v1/workflows", lan, null, String.class).getBody())
                .doesNotContain(id.toString());
    }

    @Test
    void hiddenWorkflowDetailIs404ForNonOwner() {
        UserView owner = newUser("gate-detail-owner");
        String lan = lanToken(owner, "gate-detail-lan");
        UUID id = publicWorkflow(owner).id();
        assertThat(status(HttpMethod.GET, WORKFLOWS + id, lan, null)).isEqualTo(HttpStatus.OK);

        moderate(id, "hidden");

        assertThat(status(HttpMethod.GET, WORKFLOWS + id, lan, null)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removedWorkflowRunStartIs404AndCreatesNoRun() {
        UserView owner = newUser("gate-run-owner");
        String lan = lanToken(owner, "gate-run-lan");
        UUID id = publicWorkflow(owner).id();
        moderate(id, "removed");

        assertThat(status(HttpMethod.POST, WORKFLOWS + id + "/runs", lan, Map.of("input", "hello")))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    void linkGrantNoLongerOpensAHiddenWorkflow() {
        UserView owner = newUser("gate-grant-owner");
        UserView lan = member(owner, "gate-grant-lan");
        String lanToken = loginAs(lan);
        UUID id = unlistedWorkflow(owner);
        String key = shareKey(id);
        assertThat(status(HttpMethod.GET, WORKFLOWS + id + "?key=" + key, lanToken, null)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, WORKFLOWS + id, lanToken, null)).isEqualTo(HttpStatus.OK);

        moderate(id, "hidden");

        assertThat(status(HttpMethod.GET, WORKFLOWS + id, lanToken, null)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void byKeyOfHiddenWorkflowIs404AndLogsNothing() {
        UserView owner = newUser("gate-bykey-owner");
        UUID id = unlistedWorkflow(owner);
        String key = shareKey(id);
        moderate(id, "hidden");

        assertThat(status(HttpMethod.GET, WORKFLOWS + "by-key/" + key, null, null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.GET, WORKFLOWS + "by-key/" + key, loginAs(owner), null))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(accessLogCount(id)).isZero();
    }

    @Test
    void keyAdmissionOfRemovedWorkflowIs404AndWritesNoGrant() {
        UserView owner = newUser("gate-admit-owner");
        UserView lan = member(owner, "gate-admit-lan");
        UUID id = unlistedWorkflow(owner);
        String key = shareKey(id);
        moderate(id, "removed");

        assertThat(status(HttpMethod.GET, WORKFLOWS + id + "?key=" + key, loginAs(lan), null))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_link_grant WHERE workflow_id = ?", Long.class,
                id)).isZero();
        assertThat(accessLogCount(id)).isZero();
    }

    @Test
    void ownerListsReadsEditsAndStartsHiddenWorkflow() {
        UserView owner = newUser("gate-owner-use");
        String token = loginAs(owner);
        UUID id = publicWorkflow(owner).id();
        moderate(id, "hidden");

        assertThat(call(HttpMethod.GET, "/api/v1/workflows", token, null, String.class).getBody())
                .contains(id.toString());
        assertThat(status(HttpMethod.GET, WORKFLOWS + id, token, null)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.PATCH, WORKFLOWS + id, token, Map.of("name", "renamed " + id)))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.POST, WORKFLOWS + id + "/runs", token, Map.of("input", "hello")))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void ownerOpensOwnHiddenWorkflowWithKey() {
        UserView owner = newUser("gate-owner-key");
        String token = loginAs(owner);
        UUID id = unlistedWorkflow(owner);
        String key = shareKey(id);
        moderate(id, "hidden");

        assertThat(status(HttpMethod.GET, WORKFLOWS + id + "?key=" + key, token, null)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.POST, WORKFLOWS + id + "/runs?key=" + key, token, Map.of("input", "hello")))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void nonOwnerWriteToHiddenPublicWorkflowIs404() {
        UserView owner = newUser("gate-write-owner");
        String lan = lanToken(owner, "gate-write-lan");
        UUID id = publicWorkflow(owner).id();
        assertThat(status(HttpMethod.PATCH, WORKFLOWS + id, lan, Map.of("name", "taken over")))
                .isEqualTo(HttpStatus.FORBIDDEN);

        moderate(id, "hidden");

        assertThat(status(HttpMethod.PATCH, WORKFLOWS + id, lan, Map.of("name", "taken over")))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void restoredWorkflowIsListedAndItsKeyWorksAgain() {
        UserView owner = newUser("gate-restore-owner");
        String lan = lanToken(owner, "gate-restore-lan");
        UUID publicId = publicWorkflow(owner).id();
        UUID unlistedId = unlistedWorkflow(owner);
        String key = shareKey(unlistedId);
        moderate(publicId, "hidden");
        moderate(unlistedId, "hidden");
        assertThat(status(HttpMethod.GET, WORKFLOWS + "by-key/" + key, null, null)).isEqualTo(HttpStatus.NOT_FOUND);

        moderate(publicId, "visible");
        moderate(unlistedId, "visible");

        assertThat(call(HttpMethod.GET, "/api/v1/workflows", lan, null, String.class).getBody())
                .contains(publicId.toString());
        assertThat(status(HttpMethod.GET, WORKFLOWS + "by-key/" + key, null, null)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, WORKFLOWS + unlistedId + "?key=" + key, lan, null))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void chatCatalogOmitsHiddenWorkflow() {
        UserView owner = newUser("gate-chat-owner");
        UserView lan = member(owner, "gate-chat-lan");
        UUID id = publicWorkflow(owner).id();
        assertThat(workflowService.listChatCatalog(lan.id())).anyMatch(view -> view.id().equals(id));

        moderate(id, "hidden");

        assertThat(workflowService.listChatCatalog(lan.id())).noneMatch(view -> view.id().equals(id));
        assertThat(workflowService.listChatCatalog(owner.id())).anyMatch(view -> view.id().equals(id));
    }

    @Test
    void hiddenChildOfWorkflowCallIsRefusedAsNotFound() {
        UserView owner = newUser("gate-call-owner");
        UserView lan = member(owner, "gate-call-lan");
        UUID childId = publicWorkflow(owner).id();
        WorkflowView parent = newWorkflow(lan.id(), false, new NewWorkflowStep("call", "workflow.call", null,
                "{\"workflowId\":\"" + childId + "\"}"));
        moderate(childId, "hidden");

        RunView run = awaitTerminal(lan.id(), workflowRunService.startRun(lan.id(), parent.id(), "x").id());

        assertThat(run.status()).isEqualTo("error");
        assertThat(run.steps().get(0).errorCode()).isEqualTo("workflow_call_not_found");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                childId)).isZero();
    }

    @Test
    void hiddenWorkflowVersionsAre404ForNonOwner() {
        UserView owner = newUser("gate-versions-owner");
        String lan = lanToken(owner, "gate-versions-lan");
        UUID id = publicWorkflow(owner).id();
        assertThat(status(HttpMethod.GET, WORKFLOWS + id + "/versions", lan, null)).isEqualTo(HttpStatus.OK);

        moderate(id, "hidden");

        assertThat(status(HttpMethod.GET, WORKFLOWS + id + "/versions", lan, null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.GET, WORKFLOWS + id + "/versions", loginAs(owner), null))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void hiddenWorkflowGraphIs404ForNonOwner() {
        UserView owner = newUser("gate-graph-owner");
        String lan = lanToken(owner, "gate-graph-lan");
        UUID id = publicWorkflow(owner).id();
        assertThat(status(HttpMethod.GET, WORKFLOWS + id + "/graph", lan, null)).isEqualTo(HttpStatus.OK);

        moderate(id, "hidden");

        assertThat(status(HttpMethod.GET, WORKFLOWS + id + "/graph", lan, null)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void hiddenWorkflowReviewsAre404ForNonOwner() {
        UserView owner = newUser("gate-reviews-owner");
        String lan = lanToken(owner, "gate-reviews-lan");
        UUID id = publicWorkflow(owner).id();
        assertThat(status(HttpMethod.GET, WORKFLOWS + id + "/reviews", lan, null)).isEqualTo(HttpStatus.OK);

        moderate(id, "hidden");

        assertThat(status(HttpMethod.GET, WORKFLOWS + id + "/reviews", lan, null)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void hiddenWorkflowLeavesSavedList() {
        UserView owner = newUser("gate-saved-owner");
        String lan = lanToken(owner, "gate-saved-lan");
        UUID id = publicWorkflow(owner).id();
        assertThat(status(HttpMethod.PUT, WORKFLOWS + id + "/bookmark", lan, Map.of("bookmarked", true)))
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, WORKFLOWS + "bookmarked", lan, null, String.class).getBody())
                .contains(id.toString());

        moderate(id, "hidden");

        assertThat(call(HttpMethod.GET, WORKFLOWS + "bookmarked", lan, null, String.class).getBody())
                .doesNotContain(id.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_bookmark WHERE workflow_id = ?", Long.class,
                id)).isEqualTo(1);
    }

    @Test
    void hiddenWorkflowIsNotServedAsAnAllMembersAudience() {
        UserView owner = newUser("gate-audience-owner");
        UUID id = publicWorkflow(owner).id();
        assertThat(workflowAudience.describe(id).audience()).isEqualTo(Audience.ALL_MEMBERS);

        moderate(id, "hidden");
        assertThat(workflowAudience.describe(id).audience()).isEqualTo(Audience.OWNER_ONLY);

        moderate(id, "removed");
        assertThat(workflowAudience.describe(id).audience()).isEqualTo(Audience.OWNER_ONLY);

        moderate(id, "visible");
        assertThat(workflowAudience.describe(id).audience()).isEqualTo(Audience.ALL_MEMBERS);
    }

    @Test
    void hiddenWorkflowCannotBeForked() {
        UserView owner = newUser("gate-fork-owner");
        String lan = lanToken(owner, "gate-fork-lan");
        UUID id = publicWorkflow(owner).id();
        moderate(id, "hidden");

        assertThat(status(HttpMethod.POST, WORKFLOWS + id + "/fork", lan, null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow WHERE forked_from_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    void hiddenWorkflowRunRetryIs404ForNonOwner() {
        UserView owner = newUser("gate-retry-owner");
        UserView lan = member(owner, "gate-retry-lan");
        UUID id = publicWorkflow(owner).id();
        UUID runId = seedFailedRun(lan, id);
        moderate(id, "hidden");

        assertThat(status(HttpMethod.POST, "/api/v1/runs/" + runId + "/retry", loginAs(lan), null))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class, id))
                .isEqualTo(1);
    }

    private WorkflowView publicWorkflow(UserView owner) {
        return newWorkflow(owner.id(), true, new NewWorkflowStep("only", "echo", null));
    }

    private UUID unlistedWorkflow(UserView owner) {
        UUID id = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        ResponseEntity<Map> response = call(HttpMethod.PATCH, WORKFLOWS + id + "/visibility", loginAs(owner),
                Map.of("visibility", "unlisted"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }

    private String shareKey(UUID workflowId) {
        return jdbc.queryForObject("SELECT unlisted_key FROM workflow WHERE id = ?", String.class, workflowId);
    }

    private void moderate(UUID workflowId, String status) {
        moderated.add(workflowId);
        jdbc.update("UPDATE workflow SET moderation_status = ? WHERE id = ?", status, workflowId);
    }

    /** The database is shared with the schema IT, which counts every non-visible workflow as a failure. */
    @AfterEach
    void restoreModeratedWorkflows() {
        for (UUID id : moderated) {
            jdbc.update("UPDATE workflow SET moderation_status = 'visible' WHERE id = ?", id);
        }
        moderated.clear();
    }

    private long accessLogCount(UUID workflowId) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_access_log WHERE workflow_id = ?", Long.class,
                workflowId);
    }

    private UUID seedFailedRun(UserView runOwner, UUID workflowId) {
        UUID runId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, error_message, started_at,"
                + " finished_at) VALUES (?, ?, ?, 'error', 'in', 'failed', ?, ?)", runId, workflowId, runOwner.id(),
                now, now);
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, error_code, error_message,"
                + " started_at, finished_at) VALUES (?, ?, 1, 'echo', 'error', 'in', 'lease_expired', 'failed', ?, ?)",
                UUID.randomUUID(), runId, now, now);
        return runId;
    }

    private UserView member(UserView admin, String prefix) {
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", prefix, "MEMBER");
    }

    private String lanToken(UserView admin, String prefix) {
        return loginAs(member(admin, prefix));
    }

    private HttpStatus status(HttpMethod method, String path, String token, Object body) {
        return HttpStatus.valueOf(call(method, path, token, body, String.class).getStatusCode().value());
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    @Override
    protected RunView awaitTerminal(UUID ownerId, UUID runId) {
        return pollUntil(ownerId, runId, run -> !"running".equals(run.status()), Duration.ofSeconds(30));
    }
}
