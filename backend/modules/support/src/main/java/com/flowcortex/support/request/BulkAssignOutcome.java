package com.flowcortex.support.request;

import com.flowcortex.support.request.RequestRepository.RequestRow;
import com.flowcortex.support.request.RequestStateMachine.Move;
import java.util.UUID;

/** What one number of a bulk assignment came to. */
enum BulkAssignOutcome {
    /** Decided before any write: the request needs the assignment written. */
    TO_WRITE,
    ASSIGNED,
    UNCHANGED,
    NOT_FOUND("not_found"),
    CLOSED("closed"),
    CHANGED("changed");

    private final String reason;

    BulkAssignOutcome() {
        this(null);
    }

    BulkAssignOutcome(String reason) {
        this.reason = reason;
    }

    /** The failure reason the API reports, or null for an outcome that is not a failure. */
    String reason() {
        return reason;
    }

    /**
     * Decides from the request as read ({@code null} when it does not exist) what its assignment needs:
     * nothing (it already has this assignee and would keep its status), a refusal, or a write.
     */
    static BulkAssignOutcome plan(RequestRow row, UUID assignee) {
        if (row == null) {
            return NOT_FOUND;
        }
        if (!(RequestStateMachine.next(row.status(), RequestEvent.ADMIN_ASSIGN) instanceof Move move)) {
            return CLOSED;
        }
        return assignee.equals(row.assigneeId()) && move.target() == row.status() ? UNCHANGED : TO_WRITE;
    }

    /** The outcome of a written assignment: the conditional update either won or lost to a concurrent change. */
    static BulkAssignOutcome written(boolean won) {
        return won ? ASSIGNED : CHANGED;
    }
}
