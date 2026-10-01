package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code /api/v1/workflows}, {@code /api/v1/workflows/{id}/runs}, {@code
 * /api/v1/runs/{id}} against the real, fully-wired app. Covers the
 * HTTP-level start-returns-200-before-steps-finish behavior, shared-demo
 * visibility, and the 404 shapes for an unknown/foreign workflow or run.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class WorkflowEndpointsIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void listIncludesSharedWorkflowsFromOtherOwners() {
        UserView owner = newUser("http-shared-owner");
        UserView viewer = newUser("http-shared-viewer");
        WorkflowView shared = newWorkflow(owner.id(), true, new NewWorkflowStep("only", "echo", null));
        String viewerToken = loginAs(viewer);

        ResponseEntity<WorkflowResponseView[]> response =
                get("/api/v1/workflows", viewerToken, WorkflowResponseView[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(WorkflowResponseView::id).contains(shared.id());
    }

    @Test
    void getWorkflowIncludesItsStepsInOrder() {
        UserView owner = newUser("http-detail-owner");
        WorkflowView workflow = newWorkflow(owner.id(), false,
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("second", "echo", null));
        String token = loginAs(owner);

        ResponseEntity<WorkflowResponseView> response =
                get("/api/v1/workflows/" + workflow.id(), token, WorkflowResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().steps()).extracting(WorkflowStepResponseView::stepOrder).containsExactly(1, 2);
    }

    @Test
    void eachWorkflowStepAndRunStepSaysWhetherItRunsInlineOrRemote() {
        UserView owner = newUser("http-step-mode");
        WorkflowView workflow = newWorkflow(owner.id(), false,
                new NewWorkflowStep("check", "probe", null),
                new NewWorkflowStep("echo", "echo", null));
        String token = loginAs(owner);
        UUID runId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at) "
                + "VALUES (?, ?, ?, 'running', 'clip.mp4', ?)", runId, workflow.id(), owner.id(), now);
        jdbcTemplate.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES "
                + "(?, ?, 1, 'probe', 'pending'), (?, ?, 2, 'echo', 'pending')",
                UUID.randomUUID(), runId, UUID.randomUUID(), runId);

        ResponseEntity<WorkflowResponseView> workflowResponse =
                get("/api/v1/workflows/" + workflow.id(), token, WorkflowResponseView.class);
        ResponseEntity<RunResponseView> runResponse = get("/api/v1/runs/" + runId, token, RunResponseView.class);

        assertThat(workflowResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(workflowResponse.getBody().steps()).extracting(WorkflowStepResponseView::mode)
                .containsExactly("remote", "inline");
        assertThat(runResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runResponse.getBody().steps()).extracting(RunStepResponseView::mode)
                .containsExactly("remote", "inline");
    }

    @Test
    void getUnknownWorkflowReturns404ProblemDetail() {
        UserView user = newUser("http-unknown-workflow");
        String token = loginAs(user);

        ResponseEntity<String> response = get("/api/v1/workflows/" + UUID.randomUUID(), token, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void startRunReturns200ImmediatelyThenPollingShowsCompletion() {
        UserView user = newUser("http-start-run");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(user);

        ResponseEntity<RunResponseView> startResponse =
                post("/api/v1/workflows/" + workflow.id() + "/runs", token, new StartRunRequestView("hello"),
                        RunResponseView.class);

        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        RunResponseView started = startResponse.getBody();
        assertThat(started.status()).isEqualTo("running");
        assertThat(started.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("pending"));

        RunResponseView finished = awaitTerminalHttp(token, started.id(), Duration.ofSeconds(10));
        assertThat(finished.status()).isEqualTo("completed");
        assertThat(finished.steps().get(0).output()).isEqualTo("hello");
    }

    @Test
    void getRunIsOwnerScopedForeignRunReturns404() {
        UserView owner = newUser("http-run-owner");
        UserView otherUser = newUser("http-run-other");
        WorkflowView shared = newWorkflow(owner.id(), true, new NewWorkflowStep("only", "echo", null));
        String ownerToken = loginAs(owner);
        String otherToken = loginAs(otherUser);

        ResponseEntity<RunResponseView> startResponse =
                post("/api/v1/workflows/" + shared.id() + "/runs", ownerToken, new StartRunRequestView("hi"),
                        RunResponseView.class);
        UUID runId = startResponse.getBody().id();

        ResponseEntity<String> foreignRead = get("/api/v1/runs/" + runId, otherToken, String.class);
        assertThat(foreignRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreignRead.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));

        ResponseEntity<RunResponseView> ownRead = get("/api/v1/runs/" + runId, ownerToken, RunResponseView.class);
        assertThat(ownRead.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unauthenticatedRequestsAreRejected() {
        ResponseEntity<String> response = get("/api/v1/workflows", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void suspendedUserWithStillValidTokenReturns403OnWorkflowCall() {
        // A self-owned ADMIN (newUser) can no longer suspend itself (the
        // self-lockout guard, UserServiceImpl#updateUser), so this suspends
        // a MEMBER managed by an owning admin instead — workflow access is
        // open to MEMBER too, so this still proves the same live-suspend
        // property on `/api/v1/workflows`.
        UserView owner = newUser("http-live-suspend-workflow-owner");
        UserView user = userService.createUser(owner.id(), uniqueEmail("http-live-suspend-workflow"),
                "password123", "Soon Suspended", "MEMBER");
        String token = loginAs(user);

        userService.updateUser(owner.id(), user.id(), null, null, null, "SUSPENDED");

        ResponseEntity<String> response = get("/api/v1/workflows", token, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void startRunOversizedInputReturns400NamingTheField() {
        UserView user = newUser("http-oversized-input");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(user);
        String oversizedInput = "x".repeat(10_001);

        ResponseEntity<String> response = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartRunRequestView(oversizedInput), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(response.getBody()).contains("\"input\"");
    }

    @Test
    void aRunShowsTheFileNamePickupAndOutputNamesButNeverWhereTheObjectIsStored() {
        UserView user = newUser("http-step-outputs");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "probe", null));
        String token = loginAs(user);
        UUID runId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        String upload = "{\"key\":\"source\",\"bucket\":\"flowcortex-uploads\",\"objectKey\":\"owner/upload\"}";
        jdbcTemplate.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, "
                + "finished_at) VALUES (?, ?, ?, 'completed', ?, ?, ?)", runId, workflow.id(), user.id(), "clip.mp4",
                now, now);
        jdbcTemplate.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output, outputs, "
                        + "started_at, picked_up_at, delivery_count, progress_percent, finished_at) "
                        + "VALUES (?, ?, 1, 'probe', 'completed', ?, '{}', ?::jsonb, ?, ?, 1, 100, ?)",
                UUID.randomUUID(), runId, upload, "[{\"name\":\"video-details.json\",\"bucket\":\"flowcortex-media\","
                        + "\"objectKey\":\"runs/" + runId + "/steps/1/video-details.json\","
                        + "\"mime\":\"application/json\",\"bytes\":42}]", now, now, now);

        ResponseEntity<String> response = get("/api/v1/runs/" + runId, token, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"input\":\"clip.mp4\"")
                .contains("\"pickedUpAt\"", "\"deliveryCount\":1", "\"progressPercent\":100", "\"attempt\":1")
                .contains("\"outputs\":[{\"name\":\"video-details.json\",\"mime\":\"application/json\",\"bytes\":42}]")
                .doesNotContain("objectKey", "flowcortex-media", "flowcortex-uploads", "owner/upload", "bucket");
    }

    private RunResponseView awaitTerminalHttp(String token, UUID runId, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        RunResponseView last = get("/api/v1/runs/" + runId, token, RunResponseView.class).getBody();
        while ("running".equals(last.status()) && System.currentTimeMillis() < deadline) {
            sleep();
            last = get("/api/v1/runs/" + runId, token, RunResponseView.class).getBody();
        }
        if ("running".equals(last.status())) {
            throw new AssertionError("Timed out waiting for run " + runId + " to finish — last: " + last);
        }
        return last;
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
