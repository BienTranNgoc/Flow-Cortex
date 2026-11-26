package com.flowcortex.user.phone;

/** What a sender knows about a message it did not refuse; all three count as sent. */
enum SmsDelivery {
    /** The carrier took the message. */
    ACCEPTED,
    /** The carrier already holds an identical message, so the original went out. */
    DUPLICATE,
    /** The message may have reached the carrier but no answer proves it; it is never re-sent. */
    UNCONFIRMED
}
