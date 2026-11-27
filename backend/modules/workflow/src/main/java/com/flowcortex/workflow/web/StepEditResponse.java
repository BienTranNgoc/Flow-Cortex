package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.EditableFieldView;
import com.flowcortex.workflow.api.StepEditView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A step's edit state after an edit, undo or discard. {@code editedOutput}, {@code editDiffPct} and {@code editedAt}
 * are null when the step is back to its original; {@code revision} is the token the next edit sends.
 */
record StepEditResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID runId,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID stepId,
        String editedOutput,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<EditableFieldResponse> fields,
        BigDecimal editDiffPct,
        Instant editedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) int revision) {

    static StepEditResponse from(StepEditView view) {
        return new StepEditResponse(view.runId(), view.stepId(), view.editedOutput(),
                view.fields().stream().map(EditableFieldResponse::from).toList(), view.editDiffPct(),
                view.editedAt(), view.revision());
    }

    /** One editable text with its current value; {@code index} and {@code source} are set for a list line. */
    record EditableFieldResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String pointer,
            @Schema(requiredMode = RequiredMode.REQUIRED) String label,
            Integer index,
            String source,
            @Schema(requiredMode = RequiredMode.REQUIRED) String text) {

        static EditableFieldResponse from(EditableFieldView view) {
            return new EditableFieldResponse(view.pointer(), view.label(), view.index(), view.source(), view.text());
        }
    }
}
