package com.flowcortex.entity.web;

import com.flowcortex.entity.application.EntityValidationException;
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
 * A broken entity rule renders like a field validation failure, so the form shows the sentence on the field it
 * names. Ordered first: the shared handler's catch-all would otherwise make it a 500.
 */
@RestControllerAdvice(basePackageClasses = EntityProblemHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class EntityProblemHandler {

    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    EntityProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(EntityValidationException.class)
    ProblemDetail invalid(EntityValidationException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        Map<String, String> errors = new LinkedHashMap<>();
        e.errors().forEach((field, key) -> errors.put(field, messages.getMessage(key, null, locale)));
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage("problem.validationFailed", null, locale));
        detail.setType(VALIDATION_FAILED);
        detail.setProperty("errors", errors);
        return detail;
    }
}
