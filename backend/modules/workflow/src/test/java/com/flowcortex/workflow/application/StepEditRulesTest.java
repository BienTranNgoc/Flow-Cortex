package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.application.StepEditRules.StepFacts;
import com.flowcortex.workflow.application.StepEditRules.UndoPlan;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Which steps and fields are editable, the revision guard and the undo decision, as pure functions. */
class StepEditRulesTest {

    private static StepFacts completed(String type) {
        return new StepFacts(true, type, RunStepStatus.COMPLETED, true, true);
    }

    private static String reason(StepFacts facts) {
        StepEditRules.Refusal refusal = StepEditRules.refusal(facts);
        return refusal == null ? null : refusal.reason();
    }

    @ParameterizedTest
    @ValueSource(strings = {"echo", "generate_script", "translate"})
    void editableTypeWithOutputAndFieldIsEditable(String type) {
        assertThat(StepEditRules.editable(completed(type))).isTrue();
        assertThat(StepEditRules.refusal(completed(type))).isNull();
    }

    @Test
    void runningStepIsNotCompleted() {
        assertThat(reason(new StepFacts(true, "echo", RunStepStatus.RUNNING, true, true))).isEqualTo("not_completed");
    }

    @Test
    void iterateIsFanOut() {
        assertThat(reason(completed("iterate"))).isEqualTo("fan_out");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVAL_CHECKPOINT", "qa.review"})
    void humanCheckpointsAreApproved(String type) {
        assertThat(reason(completed(type))).isEqualTo("approved_checkpoint");
    }

    @ParameterizedTest
    @ValueSource(strings = {"generate_storyboard", "generate_speech", "generate_video", "probe", "fetch_video",
            "ingest_video", "tts", "load_template", "connector.call", "drive.read", "workflow.call", "fail",
            "unknown_type"})
    void everyOtherTypeIsMachineOutput(String type) {
        assertThat(reason(completed(type))).isEqualTo("machine_output");
        assertThat(StepEditRules.editable(completed(type))).isFalse();
        assertThat(StepEditRules.refusal(completed(type)).code()).isEqualTo("step_not_editable");
    }

    @Test
    void outputWithoutItsShapeIsRefused() {
        assertThat(reason(new StepFacts(true, "generate_script", RunStepStatus.COMPLETED, true, false)))
                .isEqualTo("shape");
        assertThat(reason(new StepFacts(true, "echo", RunStepStatus.COMPLETED, true, false))).isEqualTo("shape");
    }

    @Test
    void nullOutputIsRefused() {
        assertThat(reason(new StepFacts(true, "echo", RunStepStatus.COMPLETED, false, false))).isEqualTo("no_output");
    }

    @Test
    void anItemIsRefusedFirst() {
        assertThat(reason(new StepFacts(false, "echo", RunStepStatus.COMPLETED, true, true))).isEqualTo("item");
        assertThat(reason(new StepFacts(false, "iterate", RunStepStatus.RUNNING, false, false))).isEqualTo("item");
    }

    @Test
    void theFirstMatchingRowDecides() {
        assertThat(reason(new StepFacts(true, "iterate", RunStepStatus.RUNNING, true, false)))
                .isEqualTo("not_completed");
        assertThat(reason(new StepFacts(true, "iterate", RunStepStatus.COMPLETED, false, false)))
                .isEqualTo("no_output");
    }

    @Test
    void fieldRulesPerType() {
        StepEditRules.FieldRule echo = StepEditRules.fieldsOf("echo").orElseThrow().fields().getFirst();
        assertThat(echo.pointerTemplate()).isEmpty();
        assertThat(echo.label()).isEqualTo("output");
        assertThat(echo.allowBlank()).isFalse();
        assertThat(echo.maxCharacters()).isEqualTo(100_000);

        StepEditRules.FieldRule script = StepEditRules.fieldsOf("generate_script").orElseThrow().fields().getFirst();
        assertThat(script.pointerTemplate()).isEqualTo("/script");
        assertThat(script.label()).isEqualTo("script");
        assertThat(script.allowBlank()).isFalse();
        assertThat(script.maxCharacters()).isEqualTo(8_000);

        StepEditRules.FieldRule line = StepEditRules.fieldsOf("translate").orElseThrow().fields().getFirst();
        assertThat(line.pointerTemplate()).isEqualTo("/segments/*/vi");
        assertThat(line.label()).isEqualTo("line");
        assertThat(line.allowBlank()).isTrue();
        assertThat(line.maxCharacters()).isEqualTo(1_000);

        assertThat(StepEditRules.fieldsOf("tts")).isEmpty();
    }

    @Test
    void revisionIsTheCounterPlusOne() {
        assertThat(StepEditRules.currentRevision(0)).isEqualTo(1);
        assertThat(StepEditRules.currentRevision(3)).isEqualTo(4);
    }

    @Test
    void staleRevisionIsAConflict() {
        assertThat(StepEditRules.checkRevision(2, 3)).contains("output_revision_conflict");
        assertThat(StepEditRules.checkRevision(3, 3)).isEmpty();
    }

    @Test
    void revisionBelowOneOrMissingNamesTheField() {
        assertThatThrownBy(() -> StepEditRules.validateRevision(0))
                .isInstanceOfSatisfying(FieldRefusal.class, e -> assertThat(e.field()).isEqualTo("expectedRevision"));
        assertThatThrownBy(() -> StepEditRules.validateRevision(null))
                .isInstanceOfSatisfying(FieldRefusal.class, e -> assertThat(e.field()).isEqualTo("expectedRevision"));
        assertThat(StepEditRules.validateRevision(1)).isEqualTo(1);
    }

    @Test
    void undoWithoutACurrentEditIsRefused() {
        assertThat(StepEditRules.undo(false, List.of(2, 1))).isEqualTo(new UndoPlan.Refused("nothing_to_undo"));
        assertThat(StepEditRules.undo(false, List.of())).isEqualTo(new UndoPlan.Refused("nothing_to_undo"));
    }

    @Test
    void undoRestoresThePreviousVersion() {
        assertThat(StepEditRules.undo(true, List.of(2, 1))).isEqualTo(new UndoPlan.RestoreVersion(1));
        assertThat(StepEditRules.undo(true, List.of(51, 50))).isEqualTo(new UndoPlan.RestoreVersion(50));
    }

    @Test
    void undoOfTheOnlyVersionClearsTheEdit() {
        assertThat(StepEditRules.undo(true, List.of(1))).isEqualTo(new UndoPlan.ClearEdit());
    }
}
