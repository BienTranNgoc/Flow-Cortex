package com.flowcortex.workflow.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A step's edit state after an edit, undo or discard. {@code editedOutput}, {@code editDiffPct} and
 * {@code editedAt} are {@code null} when the step is back to its original; {@code revision} is the token the next
 * edit must send; {@code fields} are the step's editable texts with their current value.
 */
public record StepEditView(UUID runId, UUID stepId, String editedOutput, List<EditableFieldView> fields,
        BigDecimal editDiffPct, Instant editedAt, int revision) {
}
