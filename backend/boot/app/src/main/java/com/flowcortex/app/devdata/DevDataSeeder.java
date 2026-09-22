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
 * Task 4.8 — dev-profile-only demo data: TWO ADMINs in DISTINCT owner scopes
 * (so J-4's cross-scope 404 has a real scope B to probe) plus one MEMBER
 * under the first ADMIN's scope, so the slice is runnable end to end
 * ({@code SPRING_PROFILES_ACTIVE=dev docker compose up}, alongside the
 * already-required {@code JWT_SECRET}).
 *
 * <p>Dev-only, never active in prod: gated by {@code @Profile("dev")}, the
 * SAME profile {@code application-dev.yml}'s JWT_SECRET fallback already
 * uses (design.md D4) — nothing here runs unless that profile is explicitly
 * selected.
 *
 * <p>Idempotent for the two realistic operating modes of a dev Postgres
 * volume — freshly created (nothing seeded yet) or fully seeded already (a
 * prior successful run) — by catching {@link ConflictException} (the email
 * already exists) and skipping rather than crashing startup. A restart that
 * lands exactly between seeding the first ADMIN and the MEMBER (a crash
 * mid-seed) is NOT recovered automatically — the member step is skipped on
 * every later restart, since by then admin1's re-attempt also conflicts and
 * its real owner id is not re-derivable from that alone. This is an accepted
 * limitation of a dev-only convenience seeder: `docker compose down -v` and
 * restarting fresh recovers it.
 */
@Configuration
@Profile("dev")
class DevDataSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String ADMIN_1_EMAIL = "admin1@flowcortex.dev";
    private static final String ADMIN_2_EMAIL = "admin2@flowcortex.dev";
    private static final String MEMBER_1_EMAIL = "member1@flowcortex.dev";

    /** Deliberately obvious dev-only credentials — never a real password. */
    private static final String ADMIN_PASSWORD = "AdminPass123!";
    private static final String MEMBER_PASSWORD = "MemberPass123!";

    // A synthetic, fixed "seed" identity for the two shared demo workflows'
    // owner_id (task 5.9) — deliberately NOT tied to a real app_user row.
    // Visibility of a shared workflow (R-049) never depends on who owns it,
    // and workflow.owner_id carries no FK constraint (design.md D1: a
    // generalizable ownership handle, not a hard reference) — this is
    // simpler and more idempotent than resolving admin1's real id across
    // restarts (which would need decoding a minted JWT here for no
    // behavioural benefit).
    private static final UUID DEMO_WORKFLOW_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-00000000d3d0");

    @Bean
    @Order(1)
    ApplicationRunner seedDemoUsers(UserService userService) {
        return args -> {
            UserView admin1 = seedAdmin(userService, ADMIN_1_EMAIL, "Demo Admin One");
            seedAdmin(userService, ADMIN_2_EMAIL, "Demo Admin Two");

            if (admin1 != null) {
                seedMember(userService, admin1.id());
            } else {
                LOG.info("[dev-seed] skipping member seed — admin1's owner id is not known this run "
                        + "(already seeded by a previous run)");
            }
        };
    }

    private static UserView seedAdmin(UserService userService, String email, String name) {
        try {
            UserView admin = userService.createInitialAdmin(email, ADMIN_PASSWORD, name);
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
     * Task 5.9 — TWO {@code is_shared=true} demo workflows so J-5/J-11 are
     * reachable end to end without wiring a real provider: an all-{@code
     * echo} success path, and one whose MIDDLE step deliberately fails (the
     * {@code fail} step type — {@code StepDispatcher}). Runs of either demo
     * still land owner-scoped to whichever user actually starts one
     * (design.md D1 — a shared DEFINITION never implies shared RUNS).
     *
     * <p>Idempotent by NAME (workflow rows carry no unique constraint on
     * {@code name} unlike {@code app_user.email}): re-running against an
     * already-seeded volume checks {@link WorkflowService#listVisibleWorkflows}
     * first and skips a demo whose name is already present, rather than
     * accumulating duplicate rows on every restart.
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
