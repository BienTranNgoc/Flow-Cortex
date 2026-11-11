package com.flowcortex.user.web;

import com.flowcortex.user.api.UnknownRoleException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Problem details for refusals of the users routes that carry a machine {@code code}. Ordered first
 * because the shared handler's catch-all would otherwise claim these exceptions.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UsersProblemHandler {

    private static final URI PROBLEM_BASE = URI.create("https://flow-cortex.dev/problems/");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    UsersProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(UnknownRoleException.class)
    ProblemDetail handleUnknownRole(UnknownRoleException ex, HttpServletRequest request) {
        String message = messages.getMessage(ex.getDetail(), localeResolver.resolveLocale(request));
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        detail.setType(PROBLEM_BASE.resolve("validation-failed"));
        detail.setProperty("code", UnknownRoleException.CODE);
        detail.setProperty("errors", Map.of("role", message));
        return detail;
    }
}
