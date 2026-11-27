package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.EditableFieldView;
import com.flowcortex.workflow.application.StepEditRules.StepFacts;
import com.flowcortex.workflow.domain.RunStep;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a run view says about editing, derived from the rows its producer already loaded: the revision of each
 * top-level step from its change counter, and whether the step is editable with the texts it offers. Every producer
 * of a run view takes it from {@link RunViewFactsFactory#of} and hands it to {@link WorkflowMapper#toRunView}, so no
 * route can answer a step's edit state or the run's {@code insightPending} with a default.
 */
record RunViewFacts(Map<UUID, StepEdit> steps, boolean insightPending) {

    /** One step's edit state; {@code fields} is empty unless {@code editable}. */
    record StepEdit(boolean editable, List<EditableFieldView> fields, int revision) {
    }

    static RunViewFacts of(List<RunStep> steps, boolean insightPending) {
        Map<UUID, StepEdit> byStep = new HashMap<>();
        for (RunStep step : steps) {
            byStep.put(step.getId(), stepEdit(step));
        }
        return new RunViewFacts(Map.copyOf(byStep), insightPending);
    }

    StepEdit step(UUID stepId) {
        StepEdit edit = steps.get(stepId);
        if (edit == null) {
            throw new IllegalStateException("No edit facts for step " + stepId + ": it was not part of the run's steps");
        }
        return edit;
    }

    private static StepEdit stepEdit(RunStep step) {
        boolean hasField = !EditedOutput.fields(step.getType(), step.getOutput()).isEmpty();
        boolean editable = StepEditRules.editable(new StepFacts(true, step.getType(), step.getStatus(),
                step.getOutput() != null, hasField));
        List<EditableFieldView> fields = editable
                ? EditedOutput.fields(step.getType(), step.effectiveOutput()).stream()
                        .map(field -> new EditableFieldView(field.pointer(), field.label(), field.index(),
                                field.source(), field.text()))
                        .toList()
                : List.of();
        return new StepEdit(editable, fields, StepEditRules.currentRevision(step.getEditSeq()));
    }
}
