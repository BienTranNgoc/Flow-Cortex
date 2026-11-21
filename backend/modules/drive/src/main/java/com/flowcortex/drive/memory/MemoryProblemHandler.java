package com.flowcortex.drive.memory;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
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
 * A refused Memory query renders like any field validation failure. Ordered first: the shared handler's
 * catch-all would otherwise turn it into a 500, so the text is put into the request's language here.
 */
@RestControllerAdvice(assignableTypes = MemoryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class MemoryProblemHandler {

    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    MemoryProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(MemoryRequestException.class)
    ProblemDetail refused(MemoryRequestException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage("problem.validationFailed", null, locale));
        detail.setType(VALIDATION_FAILED);
        Map<String, String> errors = new LinkedHashMap<>();
        e.errors().forEach((field, key) -> errors.put(field, messages.getMessage(key, null, locale)));
        detail.setProperty("errors", errors);
        return detail;
    }
}
