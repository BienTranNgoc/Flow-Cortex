package com.flowcortex.mail.links;

import com.flowcortex.mail.api.MailCategory;
import com.flowcortex.mail.api.MailSubscriptions;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link MailSubscriptions} over the same link row and history the unsubscribe routes write. */
@Component
class MemberMailSubscriptions implements MailSubscriptions {

    private final UnsubscribeLinkRepository repository;

    MemberMailSubscriptions(UnsubscribeLinkRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean subscribed(UUID memberId, MailCategory category) {
        return !repository.state(memberId).unsubscribedFrom(listOf(category));
    }

    @Override
    public boolean setSubscribed(UUID memberId, MailCategory category, boolean subscribed) {
        LinkList list = listOf(category);
        if (subscribed) {
            // A member with no row is already subscribed: nothing to clear and no row to create.
            if (!repository.state(memberId).unsubscribedFrom(list)) {
                return false;
            }
            return repository.clear(repository.ensureLinkId(memberId), memberId, list, LinkSource.PREFERENCES);
        }
        return repository.apply(repository.ensureLinkId(memberId), memberId, list, LinkSource.PREFERENCES, null);
    }

    private static LinkList listOf(MailCategory category) {
        return LinkList.of(category).orElseThrow(
                () -> new IllegalArgumentException("Mail category " + category + " has no list to join or leave"));
    }
}
