package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.application.RunFeedbackRules.Feedback;
import org.junit.jupiter.api.Test;

/** Which feedback is stored: verdict, issue and detail, decided purely. */
class RunFeedbackRulesTest {

    private static void refusedNaming(String field, String verdict, String issue, String detail) {
        assertThatThrownBy(() -> RunFeedbackRules.validate(verdict, issue, detail))
                .isInstanceOfSatisfying(FieldRefusal.class, e -> assertThat(e.field()).isEqualTo(field));
    }

    @Test
    void downWithAnIssueAndDetailIsValid() {
        assertThat(RunFeedbackRules.validate("down", "low_quality", "Too short"))
                .isEqualTo(new Feedback("down", "low_quality", "Too short"));
    }

    @Test
    void upWithNothingElseIsValid() {
        assertThat(RunFeedbackRules.validate("up", null, null)).isEqualTo(new Feedback("up", null, null));
    }

    @Test
    void downWithoutAnIssueIsRefused() {
        refusedNaming("issue", "down", null, null);
    }

    @Test
    void upWithAnIssueIsRefused() {
        refusedNaming("issue", "up", "other", null);
    }

    @Test
    void anUnknownIssueIsRefused() {
        refusedNaming("issue", "down", "bad", null);
    }

    @Test
    void anUnknownOrMissingVerdictIsRefused() {
        refusedNaming("verdict", "meh", null, null);
        refusedNaming("verdict", null, null, null);
    }

    @Test
    void aDetailOverAThousandCharactersIsRefused() {
        refusedNaming("detail", "up", null, "x".repeat(1_001));
        assertThat(RunFeedbackRules.validate("up", null, "x".repeat(1_000)).detail()).hasSize(1_000);
    }

    @Test
    void aControlCharacterIsRefused() {
        refusedNaming("detail", "up", null, "bad\u0007bell");
    }

    @Test
    void lineBreaksAndTabsAreKept() {
        assertThat(RunFeedbackRules.validate("up", null, "line one\n\tline two").detail())
                .isEqualTo("line one\n\tline two");
    }

    @Test
    void aBlankDetailIsStoredAsNull() {
        assertThat(RunFeedbackRules.validate("up", null, "  \n ").detail()).isNull();
    }
}
