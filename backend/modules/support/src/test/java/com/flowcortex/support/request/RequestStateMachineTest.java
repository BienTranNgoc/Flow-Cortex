package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.support.request.RequestStateMachine.Move;
import com.flowcortex.support.request.RequestStateMachine.Refusal;
import com.flowcortex.support.request.RequestStateMachine.Transition;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every cell of the lifecycle table: event x current status, either the target (and close reason) or the refusal. */
class RequestStateMachineTest {

    @ParameterizedTest(name = "{0} from {1} -> {2}")
    @CsvSource({
        "MEMBER_REPLY, new, new",
        "MEMBER_REPLY, in_progress, in_progress",
        "MEMBER_REPLY, waiting_member, in_progress",
        "MEMBER_REPLY, resolved, in_progress",
        "MEMBER_REPLY, closed, 409 request-closed",
        "MEMBER_CANCEL, new, closed cancelled",
        "MEMBER_CANCEL, in_progress, closed cancelled",
        "MEMBER_CANCEL, waiting_member, closed cancelled",
        "MEMBER_CANCEL, resolved, 409 transition-not-allowed",
        "MEMBER_CANCEL, closed, 409 request-closed",
        "MEMBER_CONFIRM, new, 409 transition-not-allowed",
        "MEMBER_CONFIRM, in_progress, 409 transition-not-allowed",
        "MEMBER_CONFIRM, waiting_member, 409 transition-not-allowed",
        "MEMBER_CONFIRM, resolved, closed confirmed",
        "MEMBER_CONFIRM, closed, 409 request-closed",
        "ADMIN_REPLY_WAITING_MEMBER, new, waiting_member",
        "ADMIN_REPLY_WAITING_MEMBER, in_progress, waiting_member",
        "ADMIN_REPLY_WAITING_MEMBER, waiting_member, waiting_member",
        "ADMIN_REPLY_WAITING_MEMBER, resolved, waiting_member",
        "ADMIN_REPLY_WAITING_MEMBER, closed, 409 request-closed",
        "ADMIN_REPLY_RESOLVED, new, resolved",
        "ADMIN_REPLY_RESOLVED, in_progress, resolved",
        "ADMIN_REPLY_RESOLVED, waiting_member, resolved",
        "ADMIN_REPLY_RESOLVED, resolved, resolved",
        "ADMIN_REPLY_RESOLVED, closed, 409 request-closed",
        "ADMIN_SET_IN_PROGRESS, new, in_progress",
        "ADMIN_SET_IN_PROGRESS, in_progress, 409 transition-not-allowed",
        "ADMIN_SET_IN_PROGRESS, waiting_member, in_progress",
        "ADMIN_SET_IN_PROGRESS, resolved, in_progress",
        "ADMIN_SET_IN_PROGRESS, closed, 409 transition-not-allowed",
        "ADMIN_SET_RESOLVED, new, resolved",
        "ADMIN_SET_RESOLVED, in_progress, resolved",
        "ADMIN_SET_RESOLVED, waiting_member, resolved",
        "ADMIN_SET_RESOLVED, resolved, 409 transition-not-allowed",
        "ADMIN_SET_RESOLVED, closed, 409 transition-not-allowed",
        "ADMIN_SET_CLOSED, new, closed admin",
        "ADMIN_SET_CLOSED, in_progress, closed admin",
        "ADMIN_SET_CLOSED, waiting_member, closed admin",
        "ADMIN_SET_CLOSED, resolved, closed admin",
        "ADMIN_SET_CLOSED, closed, 409 transition-not-allowed",
        "ADMIN_ASSIGN, new, in_progress",
        "ADMIN_ASSIGN, in_progress, in_progress",
        "ADMIN_ASSIGN, waiting_member, waiting_member",
        "ADMIN_ASSIGN, resolved, resolved",
        "ADMIN_ASSIGN, closed, 409 request-closed",
        "AUTO_CLOSE, new, 409 transition-not-allowed",
        "AUTO_CLOSE, in_progress, 409 transition-not-allowed",
        "AUTO_CLOSE, waiting_member, 409 transition-not-allowed",
        "AUTO_CLOSE, resolved, closed auto",
        "AUTO_CLOSE, closed, 409 transition-not-allowed",
    })
    void eachCellOfTheLifecycleTable(RequestEvent event, String from, String expected) {
        Transition transition = RequestStateMachine.next(RequestStatus.of(from), event);

        assertThat(describe(transition)).isEqualTo(expected);
    }

    @Test
    void theTableCoversEveryEventFromEveryStatus() {
        long cells = Arrays.stream(RequestEvent.values())
                .flatMap(event -> Arrays.stream(RequestStatus.values())
                        .map(status -> RequestStateMachine.next(status, event)))
                .count();

        assertThat(cells).isEqualTo(50);
    }

    private static String describe(Transition transition) {
        return switch (transition) {
            case Move move when move.closedReason() != null -> move.target().value() + " " + move.closedReason();
            case Move move -> move.target().value();
            case Refusal refusal -> "409 " + refusal.code();
        };
    }
}
