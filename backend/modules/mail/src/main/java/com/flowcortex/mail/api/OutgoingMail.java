package com.flowcortex.mail.api;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One mail to send: the catalog key, the recipient member and address, the language, and the values
 * the key's message codes index as {@code {0}}, {@code {1}}. A value may be a
 * {@link org.springframework.context.MessageSourceResolvable}, resolved in {@code locale}. The attachments,
 * none by default, are sent as parts after the text and HTML.
 */
public record OutgoingMail(MailKey key, UUID memberId, String to, Locale locale, List<Object> values,
        List<MailAttachment> attachments) {

    public OutgoingMail {
        values = values == null ? List.of() : List.copyOf(values);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** A mail with no attachment. */
    public OutgoingMail(MailKey key, UUID memberId, String to, Locale locale, List<Object> values) {
        this(key, memberId, to, locale, values, List.of());
    }
}
