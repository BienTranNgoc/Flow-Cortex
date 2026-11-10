package com.flowcortex.notification.application;

import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Renders a stored code and its arguments in the reader's language. A code missing in that language
 * reads in English (the bundle fallback); one missing everywhere reads as the generic title, or as
 * no body, never as the code.
 */
@Component
class NotificationText {

    static final String FALLBACK_TITLE = "notification.fallback.title";

    private final MessageSource messages;

    NotificationText(MessageSource messages) {
        this.messages = messages;
    }

    String title(String code, List<Object> args, Locale locale) {
        String text = render(code, args, locale);
        return text != null ? text : messages.getMessage(FALLBACK_TITLE, null, FALLBACK_TITLE, locale);
    }

    String body(String code, List<Object> args, Locale locale) {
        String text = render(code, args, locale);
        return text != null ? text : "";
    }

    private String render(String code, List<Object> args, Locale locale) {
        if (code == null) {
            return null;
        }
        return messages.getMessage(code, args.toArray(), null, locale);
    }
}
