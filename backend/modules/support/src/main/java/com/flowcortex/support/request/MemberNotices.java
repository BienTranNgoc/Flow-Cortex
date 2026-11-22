package com.flowcortex.support.request;

import com.flowcortex.notification.api.NotificationRequested;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which in-app notice a request event rings for its member, and the stable id that keeps a redelivery from
 * storing it twice. Pure: the service publishes what this plans inside the write's transaction.
 */
final class MemberNotices {

    private static final String REPLY = "support_reply";
    private static final String STATUS = "support_status";

    private MemberNotices() {
    }

    /** The notification type and the message codes its title and body render from. */
    record Notice(String type, String titleCode, String bodyCode) {

        private static Notice status(String name) {
            return new Notice(STATUS, "notification.support." + name + ".title",
                    "notification.support." + name + ".body");
        }
    }

    /** @return the notice for a change from {@code from} to {@code to}, or empty when the member is not told */
    static Optional<Notice> plan(RequestEvent event, RequestStatus from, RequestStatus to) {
        return switch (event) {
            case ADMIN_REPLY_WAITING_MEMBER, ADMIN_REPLY_RESOLVED -> Optional.of(new Notice(REPLY,
                    "notification.support.reply.title", "notification.support.reply.body"));
            case ADMIN_ASSIGN -> from == RequestStatus.NEW && to == RequestStatus.IN_PROGRESS
                    ? Optional.of(Notice.status("inProgress")) : Optional.empty();
            case ADMIN_SET_IN_PROGRESS -> Optional.of(Notice.status("inProgress"));
            case ADMIN_SET_RESOLVED -> Optional.of(Notice.status("resolved"));
            case ADMIN_SET_CLOSED, AUTO_CLOSE -> Optional.of(Notice.status("closed"));
            case MEMBER_REPLY, MEMBER_CANCEL, MEMBER_CONFIRM -> Optional.empty();
        };
    }

    /**
     * A reply's notice is keyed by its message, a status change's by the request, the new status and the instant,
     * so two changes to the same status ring twice and one redelivered change rings once.
     */
    static UUID idFor(UUID requestId, UUID messageId, RequestStatus to, Instant at) {
        String key = messageId != null ? "support:" + messageId
                : "support:" + requestId + ":" + to.value() + ":" + at.toEpochMilli();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /** The event for {@code notice}, addressed to the request's member with a link that opens the request. */
    static NotificationRequested event(Notice notice, UUID id, UUID memberId, long number) {
        return new NotificationRequested(id, memberId, notice.type(), notice.titleCode(), notice.bodyCode(),
                List.of(number), "/?help=request-" + number);
    }
}
