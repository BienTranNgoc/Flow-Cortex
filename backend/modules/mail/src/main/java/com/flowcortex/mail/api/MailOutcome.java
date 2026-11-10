package com.flowcortex.mail.api;

/** What a send did. */
public enum MailOutcome {
    SENT,
    /** The member opted out of the mail's list; nothing was sent. */
    SUPPRESSED,
    /** Rendering or delivery failed, or no transport is configured. */
    FAILED
}
