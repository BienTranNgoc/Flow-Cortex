package com.flowcortex.workflow.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
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
 * A malformed {@code Idempotency-Key} renders like a field validation failure naming the header.
 * Ordered first: the shared handler's catch-all would otherwise turn it into a 500.
 */
@RestControllerAdvice(assignableTypes = ReviewTasksController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class IdempotencyKeyRejectionHandler {

    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    IdempotencyKeyRejectionHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(IdempotencyKey.Invalid.class)
    ProblemDetail rejected(IdempotencyKey.Invalid e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage("problem.validationFailed", null, locale));
        detail.setType(VALIDATION_FAILED);
        detail.setProperty("errors", Map.of(IdempotencyKey.HEADER, messages.getMessage(e.detail(), locale)));
        return detail;
    }
}
