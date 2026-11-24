package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code iterate} — in its claim, turns the step's input list into one child step per item. It finishes
 * from its children's own rows, so it returns {@code null} and has no runtime of its own.
 */
@Component
class IterateStepExecutor implements StepExecutor {

    private final RunStepRepository runSteps;

    IterateStepExecutor(RunStepRepository runSteps) {
        this.runSteps = runSteps;
    }

    @Override
    public String type() {
        return IterateStepConfig.TYPE;
    }

    @Override
    public Mode mode() {
        return Mode.FANOUT;
    }

    /** Not a bound: the deadline of a fan-out step comes from its item count and is recorded on the step. */
    @Override
    public Duration maxRuntime() {
        return Duration.ZERO;
    }

    @Override
    public String execute(StepContext context) {
        IterateStepConfig config;
        try {
            config = IterateStepConfig.parse(context.config());
        } catch (IllegalArgumentException e) {
            throw new StepFailedException("iterate_config_invalid", e.getMessage(), e);
        }
        if (!(IterateItems.classify(context.input(), config.maxItems()) instanceof IterateItems.Items items)) {
            throw new StepFailedException("iterate_source_not_list",
                    "The input of an iterate step must be a list: a JSON array, or an object with one array field");
        }
        runSteps.saveAll(children(context, items.inputs(), config.childType()));
        return null;
    }

    /**
     * Attempt 1 starts every item pending. A retry carries over each item that an earlier attempt completed
     * on the same input, so a retried step pays only for the items that did not finish. A retry is refused
     * while an item of an earlier attempt still runs, so every item that did not complete has ended by now.
     */
    private List<RunStep> children(StepContext context, List<String> inputs, String childType) {
        short parentAttempt = context.attempt();
        Map<Integer, RunStep> earlier = new HashMap<>();
        if (parentAttempt > 1) {
            for (RunStep child : runSteps.findLatestCompletedChildren(context.stepId(), parentAttempt)) {
                earlier.put(child.getItemIndex().intValue(), child);
            }
        }
        List<RunStep> children = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            String item = inputs.get(index);
            RunStep done = earlier.get(index);
            children.add(done != null && item.equals(done.getInput())
                    ? RunStep.completedCopy(done, parentAttempt)
                    : RunStep.child(context.runId(), (short) context.stepOrder(), childType, context.stepId(),
                            parentAttempt, (short) index, item));
        }
        return children;
    }
}
