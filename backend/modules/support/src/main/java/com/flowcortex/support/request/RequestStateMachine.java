package com.flowcortex.support.request;

/** The request lifecycle table: which status an event leads to from each status, or why it is refused (409). */
final class RequestStateMachine {

    static final String REQUEST_CLOSED = "request-closed";
    static final String TRANSITION_NOT_ALLOWED = "transition-not-allowed";

    private RequestStateMachine() {
    }

    sealed interface Transition permits Move, Refusal {
    }

    /** {@code closedReason} is set exactly when {@code target} is closed. */
    record Move(RequestStatus target, String closedReason) implements Transition {

        Move(RequestStatus target) {
            this(target, null);
        }
    }

    record Refusal(String code) implements Transition {
    }

    static Transition next(RequestStatus from, RequestEvent event) {
        boolean closed = from == RequestStatus.CLOSED;
        return switch (event) {
            case MEMBER_REPLY -> closed ? new Refusal(REQUEST_CLOSED)
                    : new Move(from == RequestStatus.NEW ? RequestStatus.NEW : RequestStatus.IN_PROGRESS);
            case MEMBER_CANCEL -> closed ? new Refusal(REQUEST_CLOSED)
                    : from == RequestStatus.RESOLVED ? new Refusal(TRANSITION_NOT_ALLOWED)
                    : new Move(RequestStatus.CLOSED, "cancelled");
            case MEMBER_CONFIRM -> closed ? new Refusal(REQUEST_CLOSED)
                    : from == RequestStatus.RESOLVED ? new Move(RequestStatus.CLOSED, "confirmed")
                    : new Refusal(TRANSITION_NOT_ALLOWED);
            case ADMIN_REPLY_WAITING_MEMBER -> closed ? new Refusal(REQUEST_CLOSED)
                    : new Move(RequestStatus.WAITING_MEMBER);
            case ADMIN_REPLY_RESOLVED -> closed ? new Refusal(REQUEST_CLOSED) : new Move(RequestStatus.RESOLVED);
            case ADMIN_SET_IN_PROGRESS -> closed || from == RequestStatus.IN_PROGRESS
                    ? new Refusal(TRANSITION_NOT_ALLOWED) : new Move(RequestStatus.IN_PROGRESS);
            case ADMIN_SET_RESOLVED -> closed || from == RequestStatus.RESOLVED
                    ? new Refusal(TRANSITION_NOT_ALLOWED) : new Move(RequestStatus.RESOLVED);
            case ADMIN_SET_CLOSED -> closed ? new Refusal(TRANSITION_NOT_ALLOWED)
                    : new Move(RequestStatus.CLOSED, "admin");
            case ADMIN_ASSIGN -> closed ? new Refusal(REQUEST_CLOSED)
                    : new Move(from == RequestStatus.NEW ? RequestStatus.IN_PROGRESS : from);
            case AUTO_CLOSE -> from == RequestStatus.RESOLVED ? new Move(RequestStatus.CLOSED, "auto")
                    : new Refusal(TRANSITION_NOT_ALLOWED);
        };
    }
}
