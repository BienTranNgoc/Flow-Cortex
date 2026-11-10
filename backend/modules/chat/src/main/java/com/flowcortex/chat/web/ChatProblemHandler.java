package com.flowcortex.chat.web;

import com.flowcortex.shared.web.LocalizedMessage;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Ordered first: the shared handler's catch-all would otherwise turn a chat refusal into a 500. Because it
 * runs ahead of the shared handler, it puts the detail and the field errors into the request's language itself.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ChatProblemHandler {

    private static final URI PROBLEM_BASE = URI.create("https://flow-cortex.dev/problems/");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    ChatProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(ChatProblemException.class)
    ProblemDetail problem(ChatProblemException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(e.status(), messages.getMessage(e.detail(), locale));
        detail.setType(PROBLEM_BASE.resolve(e.code()));
        detail.setProperty("code", e.code());
        e.properties().forEach(detail::setProperty);
        if (!e.fieldErrors().isEmpty()) {
            Map<String, String> errors = new LinkedHashMap<>();
            for (Map.Entry<String, LocalizedMessage> error : e.fieldErrors().entrySet()) {
                errors.put(error.getKey(), messages.getMessage(error.getValue(), locale));
            }
            detail.setProperty("errors", errors);
        }
        return detail;
    }
}
