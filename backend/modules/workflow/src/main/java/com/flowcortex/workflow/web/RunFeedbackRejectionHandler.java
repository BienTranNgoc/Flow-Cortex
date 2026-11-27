package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.RunFeedbackRequestException;
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

/** A refused feedback value renders like any field validation failure: a 400 whose {@code errors} names it. */
@RestControllerAdvice(assignableTypes = RunFeedbackController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class RunFeedbackRejectionHandler {

    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    RunFeedbackRejectionHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(RunFeedbackRequestException.class)
    ProblemDetail rejected(RunFeedbackRequestException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage("problem.validationFailed", null, locale));
        detail.setType(VALIDATION_FAILED);
        detail.setProperty("errors", Map.of(e.field(), messages.getMessage(e.detail(), locale)));
        return detail;
    }
}
