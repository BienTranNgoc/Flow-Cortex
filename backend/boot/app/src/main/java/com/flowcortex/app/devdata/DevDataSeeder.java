package com.flowcortex.app.devdata;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowView;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;

/**
 * Dev-profile-only demo data: two ADMINs in distinct owner scopes (so a cross-scope 404 has a
 * real scope B to probe) plus one MEMBER under the first ADMIN's scope — runnable end to end via
 * {@code SPRING_PROFILES_ACTIVE=dev docker compose up}.
 *
 * <p>Gated by {@code @Profile("dev")}; never active in prod.
 *
 * <p>Idempotent across a fresh or already-seeded dev Postgres volume: catches {@link
 * ConflictException} (email exists) and skips rather than crashing startup. A crash exactly
 * between seeding the first ADMIN and the MEMBER is NOT auto-recovered — the member step stays
 * skipped on later restarts, since admin1's owner id isn't re-derivable from a conflict alone.
 * Accepted limitation of a dev-only seeder: {@code docker compose down -v} recovers it.
 */
@Configuration
@Profile("dev")
class DevDataSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String ADMIN_1_EMAIL = "admin1@flowcortex.dev";
    private static final String ADMIN_2_EMAIL = "admin2@flowcortex.dev";
    private static final String MEMBER_1_EMAIL = "member1@flowcortex.dev";
    private static final String TEST_EMAIL = "test@flowcortex.io";

    /** Deliberately obvious dev-only credentials — never a real password. */
    private static final String ADMIN_PASSWORD = "AdminPass123!";
    private static final String MEMBER_PASSWORD = "MemberPass123!";
    private static final String TEST_PASSWORD = "TestPass123!";

    // Synthetic, fixed "seed" identity for the two shared demo workflows' owner_id — not tied to
    // a real app_user row (workflow.owner_id has no FK constraint, and shared-workflow
    // visibility doesn't depend on the owner). Simpler than re-resolving admin1's id on restart.
    private static final UUID DEMO_WORKFLOW_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-00000000d3d0");

    @Bean
    @Order(1)
    ApplicationRunner seedDemoUsers(UserService userService) {
        return args -> {
            UserView admin1 = seedAdmin(userService, ADMIN_1_EMAIL, "Demo Admin One");
            seedAdmin(userService, ADMIN_2_EMAIL, "Demo Admin Two");
            seedAdmin(userService, TEST_EMAIL, TEST_PASSWORD, "FlowCortex Test User");

            if (admin1 != null) {
                seedMember(userService, admin1.id());
            } else {
                LOG.info("[dev-seed] skipping member seed — admin1's owner id is not known this run "
                        + "(already seeded by a previous run)");
            }
        };
    }

    private static UserView seedAdmin(UserService userService, String email, String name) {
        return seedAdmin(userService, email, ADMIN_PASSWORD, name);
    }

    private static UserView seedAdmin(UserService userService, String email, String password, String name) {
        try {
            UserView admin = userService.createInitialAdmin(email, password, name);
            LOG.info("[dev-seed] created ADMIN {} (owner scope {})", email, admin.id());
            return admin;
        } catch (ConflictException e) {
            LOG.info("[dev-seed] ADMIN {} already seeded, skipping", email);
            return null;
        }
    }

    private static void seedMember(UserService userService, UUID ownerId) {
        try {
            UserView member = userService.createUser(ownerId, MEMBER_1_EMAIL, MEMBER_PASSWORD, "Demo Member One",
                    "MEMBER");
            LOG.info("[dev-seed] created MEMBER {} under owner scope {}", MEMBER_1_EMAIL, member.ownerId());
        } catch (ConflictException e) {
            LOG.info("[dev-seed] MEMBER {} already seeded, skipping", MEMBER_1_EMAIL);
        }
    }

    /**
     * Three {@code is_shared=true} demo workflows so both the success and run-error UI are
     * reachable without a real provider: an all-{@code echo} success path, one whose middle
     * step deliberately fails ({@code fail} type), and one that inspects an uploaded video or
     * audio file on the media worker. Runs still land owner-scoped to whoever starts one — a
     * shared definition never implies shared runs.
     *
     * <p>Idempotent by name (no unique constraint on {@code name}, unlike {@code
     * app_user.email}): checks {@link WorkflowService#listVisibleWorkflows} first and skips an
     * already-present demo, rather than accumulating duplicates on restart.
     */
    @Bean
    @Order(2)
    ApplicationRunner seedDemoWorkflows(WorkflowService workflowService) {
        return args -> {
            seedWorkflowIfAbsent(workflowService, "Echo Demo (all steps succeed)", List.of(
                    new NewWorkflowStep("Echo step 1", "echo", null),
                    new NewWorkflowStep("Echo step 2", "echo", null),
                    new NewWorkflowStep("Echo step 3", "echo", null)));
            seedWorkflowIfAbsent(workflowService, "Failing Demo (middle step errors)", List.of(
                    new NewWorkflowStep("Echo step 1", "echo", null),
                    new NewWorkflowStep("Deliberate failure", "fail", null),
                    new NewWorkflowStep("Never reached", "echo", null)));
            seedWorkflowIfAbsent(workflowService, "Check a video file", List.of(
                    new NewWorkflowStep("Inspect the file", "probe", null),
                    new NewWorkflowStep("Extract source audio", "extract_audio", null)
                ));
        };
    }

    private static void seedWorkflowIfAbsent(WorkflowService workflowService, String name,
            List<NewWorkflowStep> steps) {
        boolean alreadySeeded = workflowService.listVisibleWorkflows(DEMO_WORKFLOW_OWNER_ID).stream()
                .anyMatch(workflow -> workflow.name().equals(name));
        if (alreadySeeded) {
            LOG.info("[dev-seed] shared demo workflow '{}' already seeded, skipping", name);
            return;
        }
        WorkflowView created = workflowService.createWorkflow(DEMO_WORKFLOW_OWNER_ID, name, true, steps);
        LOG.info("[dev-seed] created shared demo workflow '{}' ({})", name, created.id());
    }
}
