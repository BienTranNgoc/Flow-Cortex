package com.flowcortex.mail.api;

/** Why a mail is sent; decides whether a member's unsubscribe choice applies to it. */
public enum MailCategory {
    /** Account and service mail the member needs; never suppressed. */
    TRANSACTIONAL,
    /** Helpful but optional mail; stops when the member leaves the care list. */
    CARE,
    /** Marketing mail; stops when the member leaves the marketing list. */
    PROMOTIONAL
}
