package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The pure decisions of workflow reports and moderation: what each staff action means, the text rules of reports and
 * actions, and the hourly report bound. Every text refusal is a 422 naming the offending field. Status values are the
 * lowercase codes stored in the database.
 */
public final class ModerationRules {

    public static final int REPORTS_PER_HOUR = 5;
    public static final int MAX_TEXT_LENGTH = 1000;

    /** The reasons a member can report a workflow for. */
    public static final List<String> REPORT_REASONS = List.of("spam", "harmful", "scam", "inappropriate", "copyright",
            "pii_data_leak", "misleading", "other");

    private ModerationRules() {}

    /** Which entry point an action came through. */
    public enum Source {
        REPORT("report"),
        DIRECT("direct");

        private final String code;

        Source(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * The one table that says what an action means. {@code moderationStatusAfter} is null when the workflow's moderation
     * status is left as it is; {@code allowedOnDeleted} is false for every action that changes it.
     */
    public enum Action {
        DISMISS("dismiss", "dismissed", null, false, false, true),
        RESOLVE("resolve", "resolved", null, false, false, true),
        HIDE("hide", "hidden", "hidden", true, true, false),
        REMOVE("remove", "removed", "removed", true, true, false),
        RESTORE("restore", "resolved", "visible", true, true, false);

        private final String code;
        private final String reportStatusAfter;
        private final String moderationStatusAfter;
        private final boolean reasonRequired;
        private final boolean directAllowed;
        private final boolean allowedOnDeleted;

        Action(String code, String reportStatusAfter, String moderationStatusAfter, boolean reasonRequired,
                boolean directAllowed, boolean allowedOnDeleted) {
            this.code = code;
            this.reportStatusAfter = reportStatusAfter;
            this.moderationStatusAfter = moderationStatusAfter;
            this.reasonRequired = reasonRequired;
            this.directAllowed = directAllowed;
            this.allowedOnDeleted = allowedOnDeleted;
        }

        public String code() {
            return code;
        }

        public String reportStatusAfter() {
            return reportStatusAfter;
        }

        public String moderationStatusAfter() {
            return moderationStatusAfter;
        }

        public boolean reasonRequired() {
            return reasonRequired;
        }

        public boolean directAllowed() {
            return directAllowed;
        }

        public boolean allowedOnDeleted() {
            return allowedOnDeleted;
        }
    }

    /** The trimmed reason and internal note of a staff action; each is null when blank and allowed to be. */
    public record Normalized(String reason, String internalNote) {}

    /** The validated reason code and trimmed details (null when blank) of a member report. */
    public record NormalizedReport(String reason, String details) {}

    /**
     * Validates one staff action. Text refusals (422) come before the deleted-workflow refusal (404). An action the
     * direct path does not offer has no route, so reaching here with one is a caller bug.
     */
    public static Normalized checkAction(UUID workflowId, Action action, Source source, boolean workflowDeleted,
            String reason, String internalNote) {
        if (source == Source.DIRECT && !action.directAllowed()) {
            throw new IllegalArgumentException("Action " + action.code() + " is not available on the direct path");
        }
        String normalizedReason =
                text("reason", reason, new LocalizedMessage("workflow.moderation.reasonTooLong"));
        if (action.reasonRequired() && normalizedReason == null) {
            throw refusal("reason", new LocalizedMessage("workflow.moderation.reasonRequired"));
        }
        String normalizedNote =
                text("internalNote", internalNote, new LocalizedMessage("workflow.moderation.internalNoteTooLong"));
        if (workflowDeleted && !action.allowedOnDeleted()) {
            throw new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId));
        }
        return new Normalized(normalizedReason, normalizedNote);
    }

    /** Validates a member report: the reason is one of {@link #REPORT_REASONS}, the details are optional. */
    public static NormalizedReport checkReport(String reason, String details) {
        if (reason == null || !REPORT_REASONS.contains(reason)) {
            throw refusal("reason", new LocalizedMessage("workflow.report.reasonInvalid"));
        }
        return new NormalizedReport(reason,
                text("details", details, new LocalizedMessage("workflow.report.detailsTooLong")));
    }

    /** True when a member who already filed {@code countLastHour} reports in the rolling hour may not file another. */
    public static boolean overBound(long countLastHour) {
        return countLastHour >= REPORTS_PER_HOUR;
    }

    /** The trimmed text, or null when blank. Postgres cannot store U+0000 in a text column, so it is refused here. */
    private static String text(String field, String value, LocalizedMessage tooLong) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.indexOf('\u0000') >= 0) {
            throw refusal(field, new LocalizedMessage("workflow.moderation.textInvalid"));
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_TEXT_LENGTH) {
            throw refusal(field, tooLong);
        }
        return trimmed;
    }

    private static UnprocessableEntityException refusal(String field, LocalizedMessage message) {
        return new UnprocessableEntityException(message, null, Map.of(field, message));
    }
}
