package com.flowcortex.support.request;

import com.flowcortex.realtime.api.TopicAuthorizer;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.AdminPermissions;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Claims {@code support:request:<number>} topics; only staff who may read a request in the console may follow one,
 * and the hub asks again while the stream is open so a withdrawn key or a deleted request ends it.
 */
@Component("supportTopicAuthorizer")
class SupportTopicAuthorizer implements TopicAuthorizer {

    private final AdminPermissions adminPermissions;
    private final RequestRepository requests;

    SupportTopicAuthorizer(AdminPermissions adminPermissions, RequestRepository requests) {
        this.adminPermissions = adminPermissions;
        this.requests = requests;
    }

    /** Only the canonical form of a positive number is claimed: it is the form the publisher builds the topic from. */
    @Override
    public boolean supports(String topic) {
        return number(topic) > 0;
    }

    @Override
    public boolean authorize(UUID userId, String topic) {
        long number = number(topic);
        return number > 0
                && adminPermissions.holds(userId, Permission.SUPPORT_TICKET_VIEW)
                && requests.existsByNumber(number);
    }

    @Override
    public boolean revocable() {
        return true;
    }

    /** The request number the topic names, or 0 when it is not a canonical {@code support:request:<n>} topic. */
    private static long number(String topic) {
        if (topic == null || !topic.startsWith(SupportSignals.REQUEST_TOPIC_PREFIX)) {
            return 0;
        }
        String raw = topic.substring(SupportSignals.REQUEST_TOPIC_PREFIX.length());
        try {
            long parsed = Long.parseLong(raw);
            return parsed > 0 && String.valueOf(parsed).equals(raw) ? parsed : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
