package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.application.ModerationRules.Action;
import com.flowcortex.workflow.application.ModerationRules.Source;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModerationRulesTest {

    private static final UUID WORKFLOW = UUID.randomUUID();

    private static void assertRefused(Runnable call, String field, String key) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
            assertThat(e.getErrors()).containsOnlyKeys(field);
            assertThat(e.getErrors().get(field).code()).isEqualTo(key);
        });
    }

    @Test
    void dismissKeepsStatusAndSetsDismissed() {
        assertThat(Action.DISMISS.reportStatusAfter()).isEqualTo("dismissed");
        assertThat(Action.DISMISS.moderationStatusAfter()).isNull();
        assertThat(Action.DISMISS.code()).isEqualTo("dismiss");
        assertThat(Action.DISMISS.reasonRequired()).isFalse();
    }

    @Test
    void resolveKeepsStatusAndSetsResolved() {
        assertThat(Action.RESOLVE.reportStatusAfter()).isEqualTo("resolved");
        assertThat(Action.RESOLVE.moderationStatusAfter()).isNull();
        assertThat(Action.RESOLVE.code()).isEqualTo("resolve");
        assertThat(Action.RESOLVE.reasonRequired()).isFalse();
    }

    @Test
    void hideSetsHidden() {
        assertThat(Action.HIDE.reportStatusAfter()).isEqualTo("hidden");
        assertThat(Action.HIDE.moderationStatusAfter()).isEqualTo("hidden");
        assertThat(Action.HIDE.code()).isEqualTo("hide");
        assertThat(Action.HIDE.reasonRequired()).isTrue();
    }

    @Test
    void removeSetsRemoved() {
        assertThat(Action.REMOVE.reportStatusAfter()).isEqualTo("removed");
        assertThat(Action.REMOVE.moderationStatusAfter()).isEqualTo("removed");
        assertThat(Action.REMOVE.code()).isEqualTo("remove");
        assertThat(Action.REMOVE.reasonRequired()).isTrue();
    }

    @Test
    void restoreSetsVisibleAndResolvesReport() {
        assertThat(Action.RESTORE.reportStatusAfter()).isEqualTo("resolved");
        assertThat(Action.RESTORE.moderationStatusAfter()).isEqualTo("visible");
        assertThat(Action.RESTORE.code()).isEqualTo("restore");
        assertThat(Action.RESTORE.reasonRequired()).isTrue();
    }

    @Test
    void directPathRefusesDismissAndResolve() {
        assertThat(Action.DISMISS.directAllowed()).isFalse();
        assertThat(Action.RESOLVE.directAllowed()).isFalse();
        for (Action action : new Action[] {Action.HIDE, Action.REMOVE, Action.RESTORE}) {
            assertThat(action.directAllowed()).isTrue();
            assertThat(ModerationRules.checkAction(WORKFLOW, action, Source.DIRECT, false, "why", null).reason())
                    .isEqualTo("why");
        }
        for (Action action : new Action[] {Action.DISMISS, Action.RESOLVE}) {
            assertThatThrownBy(() -> ModerationRules.checkAction(WORKFLOW, action, Source.DIRECT, false, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void statusChangingActionOnDeletedWorkflowIsNotFound() {
        for (Action action : new Action[] {Action.HIDE, Action.REMOVE, Action.RESTORE}) {
            assertThat(action.allowedOnDeleted()).isFalse();
            assertThatThrownBy(
                    () -> ModerationRules.checkAction(WORKFLOW, action, Source.REPORT, true, "why", null))
                    .isInstanceOfSatisfying(NotFoundException.class, e -> {
                        assertThat(e.getDetail().code()).isEqualTo("workflow.notFound");
                        assertThat(e.getDetail().args()).containsExactly(WORKFLOW);
                    });
        }
    }

    @Test
    void dismissOnDeletedWorkflowIsAllowed() {
        for (Action action : new Action[] {Action.DISMISS, Action.RESOLVE}) {
            assertThat(action.allowedOnDeleted()).isTrue();
            assertThat(ModerationRules.checkAction(WORKFLOW, action, Source.REPORT, true, "spam", "n").reason())
                    .isEqualTo("spam");
        }
    }

    @Test
    void hideWithBlankReasonIs422Reason() {
        for (String blank : new String[] {null, "", "   ", " \n\t "}) {
            assertRefused(() -> ModerationRules.checkAction(WORKFLOW, Action.HIDE, Source.DIRECT, false, blank, null),
                    "reason", "workflow.moderation.reasonRequired");
            assertRefused(() -> ModerationRules.checkAction(WORKFLOW, Action.REMOVE, Source.REPORT, false, blank, null),
                    "reason", "workflow.moderation.reasonRequired");
            assertRefused(
                    () -> ModerationRules.checkAction(WORKFLOW, Action.RESTORE, Source.REPORT, false, blank, null),
                    "reason", "workflow.moderation.reasonRequired");
        }
    }

    @Test
    void dismissWithBlankReasonStoresNull() {
        for (String blank : new String[] {null, "", "  "}) {
            ModerationRules.Normalized normalized =
                    ModerationRules.checkAction(WORKFLOW, Action.DISMISS, Source.REPORT, false, blank, "  ");
            assertThat(normalized.reason()).isNull();
            assertThat(normalized.internalNote()).isNull();
        }
        assertThat(ModerationRules.checkAction(WORKFLOW, Action.HIDE, Source.REPORT, false, "  spam wave \n", " n ")
                .reason()).isEqualTo("spam wave");
    }

    @Test
    void reasonOver1000Is422() {
        String max = "r".repeat(1000);
        assertThat(ModerationRules.checkAction(WORKFLOW, Action.HIDE, Source.DIRECT, false, "  " + max + " ", null)
                .reason()).isEqualTo(max);
        String over = "r".repeat(1001);
        assertRefused(() -> ModerationRules.checkAction(WORKFLOW, Action.HIDE, Source.DIRECT, false, over, null),
                "reason", "workflow.moderation.reasonTooLong");
        assertRefused(() -> ModerationRules.checkAction(WORKFLOW, Action.DISMISS, Source.REPORT, false, over, null),
                "reason", "workflow.moderation.reasonTooLong");
    }

    @Test
    void noteOver1000Is422InternalNote() {
        String max = "n".repeat(1000);
        assertThat(ModerationRules.checkAction(WORKFLOW, Action.DISMISS, Source.REPORT, false, null, max)
                .internalNote()).isEqualTo(max);
        String over = "n".repeat(1001);
        assertRefused(() -> ModerationRules.checkAction(WORKFLOW, Action.DISMISS, Source.REPORT, false, null, over),
                "internalNote", "workflow.moderation.internalNoteTooLong");
    }

    @Test
    void aNullCharacterInReasonOrNoteIsRefusedBecausePostgresCannotStoreIt() {
        assertRefused(() -> ModerationRules.checkAction(WORKFLOW, Action.HIDE, Source.DIRECT, false, "a\u0000b", null),
                "reason", "workflow.moderation.textInvalid");
        assertRefused(() -> ModerationRules.checkAction(WORKFLOW, Action.DISMISS, Source.REPORT, false, null, "a\u0000"),
                "internalNote", "workflow.moderation.textInvalid");
        assertRefused(() -> ModerationRules.checkReport("spam", "a\u0000b"), "details",
                "workflow.moderation.textInvalid");
    }

    @Test
    void unknownReportReasonIs422Reason() {
        for (String bad : new String[] {null, "", "Spam", " spam", "harassment", "nope"}) {
            assertRefused(() -> ModerationRules.checkReport(bad, null), "reason", "workflow.report.reasonInvalid");
        }
        for (String good : new String[] {"spam", "harmful", "scam", "inappropriate", "copyright", "pii_data_leak",
                "misleading", "other"}) {
            assertThat(ModerationRules.checkReport(good, null).reason()).isEqualTo(good);
        }
    }

    @Test
    void blankDetailsBecomeNull() {
        for (String blank : new String[] {null, "", "  \n "}) {
            assertThat(ModerationRules.checkReport("spam", blank).details()).isNull();
        }
        assertThat(ModerationRules.checkReport("spam", "  copied my flow \n").details()).isEqualTo("copied my flow");
    }

    @Test
    void detailsOver1000Is422Details() {
        String max = "d".repeat(1000);
        assertThat(ModerationRules.checkReport("other", " " + max + " ").details()).isEqualTo(max);
        assertRefused(() -> ModerationRules.checkReport("other", "d".repeat(1001)), "details",
                "workflow.report.detailsTooLong");
    }

    @Test
    void sixthReportInTheHourIsOverTheBound() {
        assertThat(ModerationRules.REPORTS_PER_HOUR).isEqualTo(5);
        assertThat(ModerationRules.overBound(0)).isFalse();
        assertThat(ModerationRules.overBound(4)).isFalse();
        assertThat(ModerationRules.overBound(5)).isTrue();
        assertThat(ModerationRules.overBound(6)).isTrue();
    }
}
