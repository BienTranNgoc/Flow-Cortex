package com.flowcortex.app.workflow;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared setup + polling support for the workflow engine ITs. Preconditions
 * go through the {@code api} ports only ({@link UserService}, {@link
 * WorkflowService}, {@link WorkflowRunService}) — never a module's internal
 * repository — the same discipline {@code AbstractUserModuleIT} follows.
 */
abstract class AbstractWorkflowModuleIT extends AbstractPostgresBootTest {

    @Autowired
    protected UserService userService;

    @Autowired
    protected WorkflowService workflowService;

    @Autowired
    protected WorkflowRunService workflowRunService;

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    /** A fresh, self-owned member-capable user (an ADMIN, since only ADMIN rows are self-owned). */
    protected UserView newUser(String prefix) {
        return userService.createInitialAdmin(uniqueEmail(prefix), "password123", prefix);
    }

    protected WorkflowView newWorkflow(UUID ownerId, boolean shared, NewWorkflowStep... steps) {
        return workflowService.createWorkflow(ownerId, "test-workflow-" + UUID.randomUUID(), shared, List.of(steps));
    }

    /**
     * Polls {@link WorkflowRunService#getRun} until {@code condition} holds
     * or {@code timeout} elapses (async after-commit execution runs on a
     * background thread — there is no synchronous point to await
     * otherwise). Fails the test with the LAST observed run rather
     * than a bare timeout exception, so a failing assertion shows the actual
     * stuck state.
     */
    protected RunView pollUntil(UUID ownerId, UUID runId, Predicate<RunView> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        RunView last = workflowRunService.getRun(ownerId, runId);
        while (!condition.test(last) && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling run " + runId, e);
            }
            last = workflowRunService.getRun(ownerId, runId);
        }
        if (!condition.test(last)) {
            throw new AssertionError("Timed out after " + timeout + " waiting for run " + runId
                    + " to satisfy the expected condition — last observed: " + last);
        }
        return last;
    }

    protected RunView awaitTerminal(UUID ownerId, UUID runId) {
        return pollUntil(ownerId, runId, run -> !"running".equals(run.status()), Duration.ofSeconds(10));
    }
}
