package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaControlResponse;
import com.flowcortex.media.api.MediaJobControl;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkflowJobControl implements MediaJobControl {

    private record Context(WorkflowRun run, List<RunStep> steps, RunStep target, int attempt) {
    }

    private record Rule(
            String name,
            Predicate<Context> when,
            Function<Context, MediaControlResponse.Decision> decide) {
    }

    private static final List<Rule> ARBITER = List.of(
            new Rule("discard-stale-delivery",
                    ctx -> ctx.run() == null
                            || ctx.target() == null
                            || ctx.target().getAttempt() != ctx.attempt()
                            || ctx.target().getStatus() != RunStepStatus.RUNNING,
                    ctx -> MediaControlResponse.Decision.DISCARD_STALE),
            new Rule("cancel-when-sibling-failed",
                    WorkflowJobControl::siblingOrRunFailed,
                    ctx -> MediaControlResponse.Decision.CANCEL_SIBLING_FAILED),
            new Rule("continue-active-delivery",
                    ctx -> true,
                    ctx -> MediaControlResponse.Decision.CONTINUE));

    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;

    WorkflowJobControl(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository) {
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public MediaControlResponse.Decision decide(UUID runId, UUID jobId, int attempt) {
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        List<RunStep> steps = run == null ? List.of() : runStepRepository.findByRunIdOrderByStepOrderAsc(runId);
        RunStep target = steps.stream().filter(step -> step.getId().equals(jobId)).findFirst().orElse(null);
        Context ctx = new Context(run, steps, target, attempt);
        for (Rule rule : ARBITER) {
            if (rule.when().test(ctx)) {
                return rule.decide().apply(ctx);
            }
        }
        return MediaControlResponse.Decision.CONTINUE;
    }

    private static boolean siblingOrRunFailed(Context ctx) {
        if (ctx.run().getStatus() == RunStatus.ERROR) {
            return true;
        }
        return ProductVideoClipPair.match(ctx.run().getWorkflowId(), ctx.steps())
                .map(pair -> pair.siblingFailed(ctx.target().getId()))
                .orElse(false);
    }
}
