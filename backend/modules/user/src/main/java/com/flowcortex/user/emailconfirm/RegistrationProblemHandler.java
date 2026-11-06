package com.flowcortex.user.emailconfirm;

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
 * Problem details for registration and confirmation refusals, in the shape the shared
 * {@code GlobalExceptionHandler} gives every other problem plus a {@code code} or field {@code errors}.
 * Ordered first because the shared handler's catch-all would otherwise claim these exceptions.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class RegistrationProblemHandler {

    private static final URI PROBLEM_BASE = URI.create("https://flow-cortex.dev/problems/");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    RegistrationProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(EmailNotConfirmedException.class)
    ProblemDetail handleEmailNotConfirmed(EmailNotConfirmedException ex, HttpServletRequest request) {
        ProblemDetail detail = problem(HttpStatus.FORBIDDEN, "forbidden",
                messages.getMessage(ex.getDetail(), localeResolver.resolveLocale(request)));
        detail.setProperty("code", EmailNotConfirmedException.CODE);
        return detail;
    }

    @ExceptionHandler(RegistrationConflictException.class)
    ProblemDetail handleRegistrationConflict(RegistrationConflictException ex, HttpServletRequest request) {
        String message = messages.getMessage(ex.getDetail(), localeResolver.resolveLocale(request));
        ProblemDetail detail = problem(HttpStatus.CONFLICT, "conflict", message);
        detail.setProperty("errors", Map.of(ex.getField(), message));
        return detail;
    }

    private static ProblemDetail problem(HttpStatus status, String typeSlug, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setType(PROBLEM_BASE.resolve(typeSlug));
        return detail;
    }
}
