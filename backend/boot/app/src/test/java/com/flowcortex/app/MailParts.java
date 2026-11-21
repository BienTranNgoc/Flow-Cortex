package com.flowcortex.app;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;

/**
 * Reads the plain-text body of a captured mail. The transport sends multipart messages whose parts nest
 * (mixed, related, alternative), so {@code getContent()} is no longer the text itself.
 */
public final class MailParts {

    private MailParts() {
    }

    /** The first {@code text/plain} part, found by walking the message depth-first. */
    public static String textOf(MimeMessage message) throws MessagingException, IOException {
        // The parts of a message that never went through a transport carry no content type yet.
        message.saveChanges();
        Object content = message.getContent();
        if (content instanceof String text) {
            return text;
        }
        String text = content instanceof Multipart multipart ? firstPlain(multipart) : null;
        if (text == null) {
            throw new IllegalStateException("The mail has no text/plain part");
        }
        return text;
    }

    private static String firstPlain(Multipart multipart) throws MessagingException, IOException {
        for (int index = 0; index < multipart.getCount(); index++) {
            BodyPart part = multipart.getBodyPart(index);
            if (part.getContent() instanceof Multipart nested) {
                String text = firstPlain(nested);
                if (text != null) {
                    return text;
                }
            } else if (part.isMimeType("text/plain")) {
                return (String) part.getContent();
            }
        }
        return null;
    }
}
