package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 5.7 verify — {@code /api/v1/workflows}, {@code
 * /api/v1/workflows/{id}/runs}, {@code /api/v1/runs/{id}} against the real,
 * fully-wired app (design.md D3/D5). Covers J-5's HTTP-level oracle (start
 * returns 200 before steps finish, shared-demo visibility) and the 404
 * shapes for an unknown/foreign workflow or run.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class WorkflowEndpointsIT extends AbstractWorkflowHttpIT {

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
        // A self-owned ADMIN (newUser) can no longer suspend itself (G2's
        // self-lockout guard, UserServiceImpl#updateUser), so this suspends
        // a MEMBER managed by an owning admin instead — workflow access is
        // open to MEMBER too (task 5.11), so this still proves the same
        // live-suspend property on `/api/v1/workflows`.
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
