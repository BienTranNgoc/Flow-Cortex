package com.flowcortex.app.devdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The dev-profile seeder runs as part of context startup (a bare {@code
 * @SpringBootTest} invokes {@link ApplicationRunner} beans the same way
 * {@code SpringApplication.run} does), leaving TWO ADMINs in DISTINCT owner
 * scopes plus one MEMBER under the first, and re-running it against
 * already-seeded data does not crash (idempotent).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("dev")
class DevDataSeederIT extends AbstractPostgresBootTest {

    private static final String ADMIN_1_EMAIL = "admin1@flowcortex.dev";
    private static final String ADMIN_2_EMAIL = "admin2@flowcortex.dev";
    private static final String MEMBER_1_EMAIL = "member1@flowcortex.dev";
    private static final String ADMIN_PASSWORD = "AdminPass123!";
    private static final String MEMBER_PASSWORD = "MemberPass123!";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private ApplicationRunner seedDemoUsers;

    @Autowired
    @Qualifier("seedDemoWorkflows")
    private ApplicationRunner seedDemoWorkflows;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowRunService workflowRunService;

    private UUID subjectOf(String email, String password) {
        String token = authService.login(email, password, false, "127.0.0.1", "devdata").accessToken();
        Jwt jwt = jwtDecoder.decode(token);
        return UUID.fromString(jwt.getSubject());
    }

    @Test
    void seedsTwoAdminsInDistinctOwnerScopesPlusOneMemberUnderTheFirst() {
        UUID admin1Id = subjectOf(ADMIN_1_EMAIL, ADMIN_PASSWORD);
        UUID admin2Id = subjectOf(ADMIN_2_EMAIL, ADMIN_PASSWORD);
        assertThat(admin1Id).isNotEqualTo(admin2Id);

        UserView admin1 = userService.getUser(admin1Id, admin1Id);
        assertThat(admin1.role()).isEqualTo("ADMIN");
        assertThat(admin1.ownerId()).isEqualTo(admin1Id);

        UserView admin2 = userService.getUser(admin2Id, admin2Id);
        assertThat(admin2.role()).isEqualTo("ADMIN");
        assertThat(admin2.ownerId()).isEqualTo(admin2Id);

        UUID memberId = subjectOf(MEMBER_1_EMAIL, MEMBER_PASSWORD);
        UserView member = userService.getUser(admin1Id, memberId);
        assertThat(member.role()).isEqualTo("MEMBER");
        assertThat(member.ownerId()).isEqualTo(admin1Id);

        // Cross-scope: admin2 cannot see the member seeded under admin1's
        // scope, using real seeded data.
        assertThrows(NotFoundException.class, () -> userService.getUser(admin2Id, memberId));
    }

    @Test
    void reRunningTheSeederAgainstAlreadySeededDataDoesNotCrash() {
        assertThatCode(() -> seedDemoUsers.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    /**
     * The two {@code is_shared=true} demo workflows: visible to ANY
     * authenticated user, one all-{@code echo} success path, one whose
     * middle step deliberately fails, so a failing run is reachable without
     * a real provider.
     */
    @Test
    void seedsTwoSharedDemoWorkflowsOneSucceedingOneFailingAtItsMiddleStep() {
        UserView viewer = userService.createInitialAdmin(
                "workflow-seed-viewer-" + UUID.randomUUID() + "@example.com", "password123", "Workflow Seed Viewer");

        List<WorkflowView> visible = workflowService.listVisibleWorkflows(viewer.id());
        WorkflowView echoDemo = visible.stream()
                .filter(w -> w.name().equals("Echo Demo (all steps succeed)"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Echo demo workflow not seeded"));
        WorkflowView failingDemo = visible.stream()
                .filter(w -> w.name().equals("Failing Demo (middle step errors)"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Failing demo workflow not seeded"));
        assertThat(echoDemo.shared()).isTrue();
        assertThat(failingDemo.shared()).isTrue();

        RunView echoRun = workflowRunService.startRun(viewer.id(), echoDemo.id(), "hello");
        RunView finishedEcho = awaitTerminal(viewer.id(), echoRun.id());
        assertThat(finishedEcho.status()).isEqualTo("completed");

        RunView failingRun = workflowRunService.startRun(viewer.id(), failingDemo.id(), "hello");
        RunView finishedFailing = awaitTerminal(viewer.id(), failingRun.id());
        assertThat(finishedFailing.status()).isEqualTo("error");
        assertThat(finishedFailing.steps().get(0).status()).isEqualTo("completed");
        assertThat(finishedFailing.steps().get(1).status()).isEqualTo("error");
        assertThat(finishedFailing.steps().get(2).status()).isEqualTo("pending");
    }

    @Test
    void seedsASharedCheckAVideoFileWorkflowWhoseOnlyStepInspectsTheUpload() {
        UserView viewer = userService.createInitialAdmin(
                "workflow-seed-media-" + UUID.randomUUID() + "@example.com", "password123", "Media Seed Viewer");

        WorkflowView check = workflowService.listVisibleWorkflows(viewer.id()).stream()
                .filter(w -> w.name().equals("Check a video file"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Check a video file workflow not seeded"));

        assertThat(check.shared()).isTrue();
        assertThat(check.steps()).extracting(step -> step.name())
            .containsExactly("Inspect the file", "Extract source audio");
        assertThat(check.steps()).extracting(step -> step.type())
            .containsExactly("probe", "extract_audio");
        assertThat(check.steps()).extracting(step -> step.mode())
            .containsExactly("remote", "remote");
    }

    @Test
    void reRunningTheWorkflowSeederAgainstAlreadySeededDataDoesNotDuplicate() throws Exception {
        seedDemoWorkflows.run(new DefaultApplicationArguments());

        UserView viewer = userService.createInitialAdmin(
                "workflow-seed-dup-check-" + UUID.randomUUID() + "@example.com", "password123", "Dup Check Viewer");
        long echoDemoCount = workflowService.listVisibleWorkflows(viewer.id()).stream()
                .filter(w -> w.name().equals("Echo Demo (all steps succeed)"))
                .count();

        assertThat(echoDemoCount).isEqualTo(1);
    }

    private RunView awaitTerminal(UUID ownerId, UUID runId) {
        Instant deadline = Instant.now().plusSeconds(10);
        RunView last = workflowRunService.getRun(ownerId, runId);
        while ("running".equals(last.status()) && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling run " + runId, e);
            }
            last = workflowRunService.getRun(ownerId, runId);
        }
        if ("running".equals(last.status())) {
            throw new AssertionError("Timed out waiting for run " + runId + " to finish — last: " + last);
        }
        return last;
    }
}
