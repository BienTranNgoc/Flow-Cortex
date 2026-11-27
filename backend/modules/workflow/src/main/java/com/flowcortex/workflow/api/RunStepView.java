package com.flowcortex.workflow.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of a {@code run_step} row. {@code status} is the exact
 * lowercase wire value ({@code pending}|{@code running}|{@code completed}|
 * {@code error}) the acceptance oracles assert on directly. A {@code running}
 * step with no {@code pickedUpAt} is still waiting for an executor; {@code
 * outputs} is empty unless the step produced objects. {@code mode} is {@code inline} or {@code
 * remote}, read from the step's own type, so it holds even after the workflow is edited. {@code
 * children} holds an iterate step's items of its current attempt in item order and is empty for any
 * other step.
 *
 * <p>{@code output} is always the engine's original result. A member's correction sits beside it:
 * {@code editedOutput} is the whole result with the edited texts applied ({@code null} when no edit is current, as
 * are {@code editedAt} and {@code editDiffPct}), {@code editable} says whether the step may be edited,
 * {@code editableFields} are its editable texts with their current value (empty unless editable), and
 * {@code editRevision} is the token an edit must send back (1 for an unedited step).
 */
public record RunStepView(
        UUID id, int stepOrder, String type, String status, String input, String output, String errorMessage,
        Instant startedAt, Instant finishedAt, Integer progressPercent, int attempt, String errorCode,
        int deliveryCount, Instant pickedUpAt, List<RunStepOutputView> outputs, String mode,
        List<RunStepChildView> children, boolean editable, List<EditableFieldView> editableFields,
        String editedOutput, Instant editedAt, BigDecimal editDiffPct, int editRevision) {

    /** A step without items. */
    public RunStepView(UUID id, int stepOrder, String type, String status, String input, String output,
            String errorMessage, Instant startedAt, Instant finishedAt, Integer progressPercent, int attempt,
            String errorCode, int deliveryCount, Instant pickedUpAt, List<RunStepOutputView> outputs, String mode) {
        this(id, stepOrder, type, status, input, output, errorMessage, startedAt, finishedAt, progressPercent,
                attempt, errorCode, deliveryCount, pickedUpAt, outputs, mode, List.of());
    }

    /** A step with items and no edit state, for callers in other modules that build a view by hand. */
    public RunStepView(UUID id, int stepOrder, String type, String status, String input, String output,
            String errorMessage, Instant startedAt, Instant finishedAt, Integer progressPercent, int attempt,
            String errorCode, int deliveryCount, Instant pickedUpAt, List<RunStepOutputView> outputs, String mode,
            List<RunStepChildView> children) {
        this(id, stepOrder, type, status, input, output, errorMessage, startedAt, finishedAt, progressPercent,
                attempt, errorCode, deliveryCount, pickedUpAt, outputs, mode, children, false, List.of(), null, null,
                null, 1);
    }
}
