package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Identifies the Product Video parallel clip pair in a run snapshot (showroom legacy 5–6 or MC 7–8). */
final class ProductVideoClipPair {

    private static final int STORYBOARD_ORDER = 3;
    private static final int FIRST_CLIP_ORDER = 5;
    private static final int SECOND_CLIP_ORDER = 6;
    private static final int MC_STORYBOARD_ORDER = 4;
    private static final int MC_FIRST_CLIP_ORDER = 7;
    private static final int MC_SECOND_CLIP_ORDER = 8;
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

        Optional<RunStep> siblingOf(UUID stepId) {
            if (firstClip.getId().equals(stepId)) {
                return Optional.of(secondClip);
            }
            return secondClip.getId().equals(stepId) ? Optional.of(firstClip) : Optional.empty();
        }
    }

    private ProductVideoClipPair() {
    }

    static Optional<Pair> match(UUID workflowId, List<RunStep> steps) {
        if (ProductVideoWorkflow.ID.equals(workflowId)) {
            return matchPair(steps, STORYBOARD_ORDER, FIRST_CLIP_ORDER, SECOND_CLIP_ORDER);
        }
        if (ProductVideoWorkflow.MC_ID.equals(workflowId)) {
            return matchPair(steps, MC_STORYBOARD_ORDER, MC_FIRST_CLIP_ORDER, MC_SECOND_CLIP_ORDER);
        }
        return Optional.empty();
    }

    private static Optional<Pair> matchPair(List<RunStep> steps, int storyboardOrder, int firstClipOrder,
            int secondClipOrder) {
        if (steps == null || steps.size() < secondClipOrder) {
            return Optional.empty();
        }
        Map<Integer, RunStep> byOrder = new HashMap<>();
        for (RunStep step : steps) {
            if (byOrder.put((int) step.getStepOrder(), step) != null) {
                return Optional.empty();
            }
        }
        List<RunStep> prerequisites = new ArrayList<>(firstClipOrder - 1);
        for (int order = 1; order < firstClipOrder; order++) {
            RunStep prerequisite = byOrder.get(order);
            if (prerequisite == null) {
                return Optional.empty();
            }
            prerequisites.add(prerequisite);
        }
        RunStep storyboard = byOrder.get(storyboardOrder);
        RunStep firstClip = byOrder.get(firstClipOrder);
        RunStep secondClip = byOrder.get(secondClipOrder);
        if (storyboard == null || firstClip == null || secondClip == null) {
            return Optional.empty();
        }
        if (!STORYBOARD_TYPE.equals(storyboard.getType())
                || !VIDEO_TYPE.equals(firstClip.getType())
                || !VIDEO_TYPE.equals(secondClip.getType())) {
            return Optional.empty();
        }
        if (!referencesStepOrder(firstClip.getInputRef(), storyboardOrder)
                || !referencesStepOrder(secondClip.getInputRef(), storyboardOrder)) {
            return Optional.empty();
        }
        return Optional.of(new Pair(List.copyOf(prerequisites), firstClip, secondClip));
    }

    private static boolean referencesStepOrder(String inputRef, int order) {
        return StepReferences.parse(inputRef)
                .map(ref -> ref.head() instanceof StepReferences.Order target && target.value() == order)
                .orElse(false);
    }
}
