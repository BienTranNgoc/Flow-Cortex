package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.support.request.MemberNotices.Notice;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberNoticesTest {

    private static final Instant AT = Instant.parse("2026-11-11T08:00:00Z");

    @Test
    void bothAdminRepliesRingTheReplyNoticeWhateverTheyDoToTheStatus() {
        Notice reply = new Notice("support_reply", "notification.support.reply.title",
                "notification.support.reply.body");

        assertThat(MemberNotices.plan(RequestEvent.ADMIN_REPLY_WAITING_MEMBER, RequestStatus.NEW,
                RequestStatus.WAITING_MEMBER)).contains(reply);
        assertThat(MemberNotices.plan(RequestEvent.ADMIN_REPLY_RESOLVED, RequestStatus.IN_PROGRESS,
                RequestStatus.RESOLVED)).contains(reply);
    }

    @Test
    void aPickUpAndAnAdminMoveToInProgressRingTheInProgressNotice() {
        Notice inProgress = new Notice("support_status", "notification.support.inProgress.title",
                "notification.support.inProgress.body");

        assertThat(MemberNotices.plan(RequestEvent.ADMIN_ASSIGN, RequestStatus.NEW, RequestStatus.IN_PROGRESS))
                .contains(inProgress);
        assertThat(MemberNotices.plan(RequestEvent.ADMIN_SET_IN_PROGRESS, RequestStatus.WAITING_MEMBER,
                RequestStatus.IN_PROGRESS)).contains(inProgress);
    }

    @Test
    void anAdminResolveRingsTheResolvedNotice() {
        assertThat(MemberNotices.plan(RequestEvent.ADMIN_SET_RESOLVED, RequestStatus.IN_PROGRESS,
                RequestStatus.RESOLVED)).contains(new Notice("support_status", "notification.support.resolved.title",
                        "notification.support.resolved.body"));
    }

    @Test
    void anAdminCloseAndTheAutoCloseRingTheClosedNotice() {
        Notice closed = new Notice("support_status", "notification.support.closed.title",
                "notification.support.closed.body");

        assertThat(MemberNotices.plan(RequestEvent.ADMIN_SET_CLOSED, RequestStatus.IN_PROGRESS,
                RequestStatus.CLOSED)).contains(closed);
        assertThat(MemberNotices.plan(RequestEvent.AUTO_CLOSE, RequestStatus.RESOLVED, RequestStatus.CLOSED))
                .contains(closed);
    }

    @Test
    void anAssignmentThatKeepsTheStatusAndEveryMemberEventRingNothing() {
        assertThat(MemberNotices.plan(RequestEvent.ADMIN_ASSIGN, RequestStatus.IN_PROGRESS,
                RequestStatus.IN_PROGRESS)).isEmpty();
        assertThat(MemberNotices.plan(RequestEvent.ADMIN_ASSIGN, RequestStatus.RESOLVED, RequestStatus.RESOLVED))
                .isEmpty();
        Arrays.stream(RequestEvent.values()).filter(event -> event.name().startsWith("MEMBER_"))
                .forEach(event -> assertThat(MemberNotices.plan(event, RequestStatus.WAITING_MEMBER,
                        RequestStatus.IN_PROGRESS)).as(event.name()).isEmpty());
    }

    @Test
    void aReplyKeepsOneIdForItsMessageSoARedeliveryStoresNothingNew() {
        UUID request = UUID.randomUUID();
        UUID message = UUID.randomUUID();

        assertThat(MemberNotices.idFor(request, message, RequestStatus.RESOLVED, AT))
                .isEqualTo(MemberNotices.idFor(request, message, RequestStatus.RESOLVED, AT.plusSeconds(60)));
        assertThat(MemberNotices.idFor(request, message, RequestStatus.RESOLVED, AT))
                .isNotEqualTo(MemberNotices.idFor(request, UUID.randomUUID(), RequestStatus.RESOLVED, AT));
    }

    @Test
    void twoStatusChangesToTheSameStatusAtDifferentInstantsGetDifferentIds() {
        UUID request = UUID.randomUUID();

        assertThat(MemberNotices.idFor(request, null, RequestStatus.IN_PROGRESS, AT))
                .isEqualTo(MemberNotices.idFor(request, null, RequestStatus.IN_PROGRESS, AT));
        assertThat(MemberNotices.idFor(request, null, RequestStatus.IN_PROGRESS, AT))
                .isNotEqualTo(MemberNotices.idFor(request, null, RequestStatus.IN_PROGRESS, AT.plusMillis(1)));
        assertThat(MemberNotices.idFor(request, null, RequestStatus.IN_PROGRESS, AT))
                .isNotEqualTo(MemberNotices.idFor(request, null, RequestStatus.RESOLVED, AT));
    }
}
