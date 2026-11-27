package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.RunStepStatus;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Pure decisions of a step output edit: which steps and which of their text fields are editable, the revision
 * token, and what an undo does. Two ordered tables in the style of {@code WorkflowCallRules.REFUSALS}: the first
 * row that applies wins.
 */
final class StepEditRules {

    static final String NOT_EDITABLE = "step_not_editable";
    static final String REVISION_CONFLICT = "output_revision_conflict";
    static final String NOTHING_TO_UNDO = "nothing_to_undo";

    /** The sum of the field texts of one request. */
    static final int MAX_EDIT_CHARACTERS = 100_000;
    static final int MAX_FIELDS = 2_000;
    static final int MAX_VERSIONS = 50;

    /** What the rules read of a step; {@code hasEditableField} comes from {@code EditedOutput.fields}. */
    record StepFacts(boolean topLevel, String type, RunStepStatus status, boolean hasOutput,
            boolean hasEditableField) {
    }

    /**
     * One editable string of a type: {@code pointerTemplate} is a JSON Pointer where {@code *} stands for one array
     * index, and the empty template is the whole output as plain text.
     */
    record FieldRule(String pointerTemplate, String label, boolean allowBlank, int maxCharacters) {
    }

    record FieldRow(String type, List<FieldRule> fields) {
    }

    /** Why a step is not editable; {@code code} is always {@link #NOT_EDITABLE}. */
    record Refusal(String code, String reason) {
    }

    private record Row(Predicate<StepFacts> applies, String reason) {
    }

    static final List<FieldRow> EDITABLE_FIELDS = List.of(
            new FieldRow("echo", List.of(new FieldRule("", "output", false, MAX_EDIT_CHARACTERS))),
            new FieldRow("generate_script", List.of(new FieldRule("/script", "script", false, 8_000))),
            new FieldRow("translate", List.of(new FieldRule("/segments/*/vi", "line", true, 1_000))));

    private static final List<Row> REFUSALS = List.of(
            new Row(f -> !f.topLevel(), "item"),
            new Row(f -> f.status() != RunStepStatus.COMPLETED, "not_completed"),
            new Row(f -> !f.hasOutput(), "no_output"),
            new Row(f -> HumanSteps.TYPES.contains(f.type()), "approved_checkpoint"),
            new Row(f -> "iterate".equals(f.type()), "fan_out"),
            new Row(f -> fieldsOf(f.type()).isEmpty(), "machine_output"),
            new Row(f -> !f.hasEditableField(), "shape"));

    /** What an undo does, decided from whether an edit is current and the two newest stored versions. */
    sealed interface UndoPlan permits UndoPlan.RestoreVersion, UndoPlan.ClearEdit, UndoPlan.Refused {

        /** The earlier version's field values become the current edit, as a new version. */
        record RestoreVersion(int version) implements UndoPlan {
        }

        /** Only one version exists: the step goes back to its original. */
        record ClearEdit() implements UndoPlan {
        }

        record Refused(String code) implements UndoPlan {
        }
    }

    private StepEditRules() {
    }

    static boolean editable(StepFacts facts) {
        return refusal(facts) == null;
    }

    /** The first refusal that applies, or null when the step is editable. */
    static Refusal refusal(StepFacts facts) {
        return REFUSALS.stream().filter(row -> row.applies().test(facts))
                .map(row -> new Refusal(NOT_EDITABLE, row.reason())).findFirst().orElse(null);
    }

    static Optional<FieldRow> fieldsOf(String type) {
        return EDITABLE_FIELDS.stream().filter(row -> row.type().equals(type)).findFirst();
    }

    /** The revision a step is at: its change counter plus one, so the original is revision 1. */
    static int currentRevision(int editSeq) {
        return editSeq + 1;
    }

    /** The conflict code when the tab's revision is not the step's current one. */
    static Optional<String> checkRevision(int expected, int current) {
        return expected == current ? Optional.empty() : Optional.of(REVISION_CONFLICT);
    }

    /** @throws FieldRefusal naming {@code expectedRevision} when absent or below 1 */
    static int validateRevision(Integer expected) {
        if (expected == null || expected < 1) {
            throw new FieldRefusal("expectedRevision", FieldRefusal.Reason.INVALID);
        }
        return expected;
    }

    /** @param newestFirst the stored version numbers of the step, newest first, at most the two newest are read */
    static UndoPlan undo(boolean hasCurrentEdit, List<Integer> newestFirst) {
        if (!hasCurrentEdit) {
            return new UndoPlan.Refused(NOTHING_TO_UNDO);
        }
        return newestFirst.size() >= 2 ? new UndoPlan.RestoreVersion(newestFirst.get(1)) : new UndoPlan.ClearEdit();
    }
}
