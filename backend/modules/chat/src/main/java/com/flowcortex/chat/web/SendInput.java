package com.flowcortex.chat.web;

import com.flowcortex.chat.cards.Attachment;
import com.flowcortex.chat.cards.Attachments;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** The request rules of a send, shared by the send route and create-with-first-message. */
final class SendInput {

    static final int MAX_TEXT_CHARS = 4000;
    static final int MAX_ATTACHMENTS = 10;
    static final int MAX_KEY_CHARS = 80;

    /** A message that passed the rules: its text trimmed, its attachments never null and their names stripped. */
    record Message(String text, List<Attachment> attachments) {
    }

    private SendInput() {
    }

    /** The {@code Idempotency-Key} header, trimmed. */
    static String key(String header) {
        String key = header == null ? "" : header.strip();
        if (key.isEmpty() || key.length() > MAX_KEY_CHARS) {
            throw refused(Map.of("Idempotency-Key",
                    new LocalizedMessage("chat.idempotency.key.invalid", MAX_KEY_CHARS)));
        }
        return key;
    }

    static Message message(String text, List<Attachment> attachments) {
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_TEXT_CHARS) {
            errors.put("text", new LocalizedMessage("chat.message.text.invalid", MAX_TEXT_CHARS));
        }
        List<Attachment> cleaned = Attachments.clean(attachments, 0, MAX_ATTACHMENTS).orElse(null);
        if (cleaned == null) {
            errors.put("attachments", new LocalizedMessage("chat.message.attachments.invalid", MAX_ATTACHMENTS,
                    Attachment.MAX_FILE_NAME_CHARS));
        }
        if (!errors.isEmpty()) {
            throw refused(errors);
        }
        return new Message(trimmed, cleaned);
    }

    /** {@code vi} when the request's language is Vietnamese, {@code en} otherwise. */
    static String language(Locale locale) {
        return "vi".equals(locale.getLanguage()) ? "vi" : "en";
    }

    private static ChatProblemException refused(Map<String, LocalizedMessage> errors) {
        return new ChatProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                new LocalizedMessage("problem.validationFailed")).withFieldErrors(errors);
    }
}
