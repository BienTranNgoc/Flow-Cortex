package com.flowcortex.mail.api;

/** The one way a module sends mail. */
public interface Mailer {

    /**
     * Sends the mail, or reports why it was not.
     *
     * @return {@code SENT}, {@code SUPPRESSED} or {@code FAILED}; a refused relay, an unrenderable value or
     *         a missing transport is {@code FAILED}, never an exception
     * @throws IllegalArgumentException when the mail has no key, member, address or locale, or its
     *         number of values differs from the key's; nothing is sent
     */
    MailOutcome send(OutgoingMail mail);
}
