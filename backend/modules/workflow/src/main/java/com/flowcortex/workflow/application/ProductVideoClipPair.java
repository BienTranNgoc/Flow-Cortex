package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Identifies the Product Video parallel clip pair (steps 5–6 referencing storyboard step 3) in a run snapshot. */
final class ProductVideoClipPair {

    private static final int STORYBOARD_ORDER = 3;
    private static final int FIRST_CLIP_ORDER = 5;
    private static final int SECOND_CLIP_ORDER = 6;
    private static final String STORYBOARD_TYPE = "generate_storyboard";
    private static final String VIDEO_TYPE = "generate_video";

    record Pair(List<RunStep> prerequisites, RunStep firstClip, RunStep secondClip) {

        boolean prerequisitesCompleted() {
            return prerequisites.stream().allMatch(step -> step.getStatus() == RunStepStatus.COMPLETED);
        }

        boolean siblingFailed(UUID stepId) {
            if (firstClip.getId().equals(stepId)) {
                return secondClip.getStatus() == RunStepStatus.ERROR;
            }
            if (secondClip.getId().equals(stepId)) {
                return firstClip.getStatus() == RunStepStatus.ERROR;
            }
            return false;
        }
    }

    private ProductVideoClipPair() {
    }

    static Optional<Pair> match(UUID workflowId, List<RunStep> steps) {
        if (!ProductVideoWorkflow.ID.equals(workflowId) || steps == null || steps.size() < SECOND_CLIP_ORDER) {
            return Optional.empty();
        }
        Map<Integer, RunStep> byOrder = new HashMap<>();
        for (RunStep step : steps) {
            if (byOrder.put((int) step.getStepOrder(), step) != null) {
                return Optional.empty();
            }
        }
        RunStep step1 = byOrder.get(1);
        RunStep step2 = byOrder.get(2);
        RunStep step3 = byOrder.get(STORYBOARD_ORDER);
        RunStep step4 = byOrder.get(4);
        RunStep step5 = byOrder.get(FIRST_CLIP_ORDER);
        RunStep step6 = byOrder.get(SECOND_CLIP_ORDER);
        if (step1 == null || step2 == null || step3 == null || step4 == null || step5 == null || step6 == null) {
            return Optional.empty();
        }
        if (!STORYBOARD_TYPE.equals(step3.getType())
                || !VIDEO_TYPE.equals(step5.getType())
                || !VIDEO_TYPE.equals(step6.getType())) {
            return Optional.empty();
        }
        if (!referencesStepOrder(step5.getInputRef(), STORYBOARD_ORDER)
                || !referencesStepOrder(step6.getInputRef(), STORYBOARD_ORDER)) {
            return Optional.empty();
        }
        return Optional.of(new Pair(List.of(step1, step2, step3, step4), step5, step6));
    }

    private static boolean referencesStepOrder(String inputRef, int order) {
        return StepReferences.parse(inputRef)
                .map(ref -> ref.head() instanceof StepReferences.Order target && target.value() == order)
                .orElse(false);
    }
}
