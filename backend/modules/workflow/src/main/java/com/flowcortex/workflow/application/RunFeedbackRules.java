package com.flowcortex.workflow.application;

import com.flowcortex.workflow.application.FieldRefusal.Reason;
import java.util.Set;

/** What feedback on a run is stored: a verdict, for a thumbs-down one issue, and an optional bounded detail. */
final class RunFeedbackRules {

    static final String UP = "up";
    static final String DOWN = "down";
    static final Set<String> ISSUES = Set.of("wrong_request", "low_quality", "off_brand", "technical_error",
            "inappropriate", "other");
    static final int MAX_DETAIL_CHARACTERS = 1_000;

    /** The validated feedback; {@code detail} is null when none was given or it was blank. */
    record Feedback(String verdict, String issue, String detail) {
    }

    private RunFeedbackRules() {
    }

    /** @throws FieldRefusal naming {@code verdict}, {@code issue} or {@code detail} */
    static Feedback validate(String verdict, String issue, String detail) {
        if (!UP.equals(verdict) && !DOWN.equals(verdict)) {
            throw new FieldRefusal("verdict", Reason.INVALID);
        }
        boolean down = DOWN.equals(verdict);
        if (down ? issue == null || !ISSUES.contains(issue) : issue != null) {
            throw new FieldRefusal("issue", Reason.INVALID);
        }
        return new Feedback(verdict, issue, checkedDetail(detail));
    }

    private static String checkedDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        if (detail.length() > MAX_DETAIL_CHARACTERS) {
            throw new FieldRefusal("detail", Reason.TOO_LONG);
        }
        if (detail.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw new FieldRefusal("detail", Reason.INVALID);
        }
        return detail;
    }
}
