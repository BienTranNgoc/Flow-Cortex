package com.flowcortex.mail.api;

import java.util.UUID;

/**
 * A member's choice to receive the care and promotional mail lists: the same state the email
 * unsubscribe link writes. A member who never chose is subscribed.
 */
public interface MailSubscriptions {

    /**
     * @throws IllegalArgumentException for {@link MailCategory#TRANSACTIONAL}, which no member can leave
     */
    boolean subscribed(UUID memberId, MailCategory category);

    /**
     * Joins or leaves the category's list and records the change in the member's history.
     *
     * @return whether the state changed; setting the current value changes and records nothing
     * @throws IllegalArgumentException for {@link MailCategory#TRANSACTIONAL}
     */
    boolean setSubscribed(UUID memberId, MailCategory category, boolean subscribed);
}
