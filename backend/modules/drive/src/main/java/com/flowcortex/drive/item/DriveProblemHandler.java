package com.flowcortex.drive.item;

import com.flowcortex.media.api.UploadRejectedException;
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
 * A broken Drive rule or a refused upload renders like a field validation failure, so the form shows the
 * sentence on the field it names. Ordered first: the shared handler's catch-all would otherwise make it a 500.
 */
@RestControllerAdvice(assignableTypes = {DriveItemsController.class, DriveUploadsController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
class DriveProblemHandler {

    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    DriveProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(DriveValidationException.class)
    ProblemDetail invalid(DriveValidationException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        Map<String, String> errors = new LinkedHashMap<>();
        e.errors().forEach((field, key) -> errors.put(field, messages.getMessage(key, null, locale)));
        return problem(errors, locale);
    }

    @ExceptionHandler(UploadRejectedException.class)
    ProblemDetail rejected(UploadRejectedException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        return problem(Map.of(e.field(), messages.getMessage(e.detail(), locale)), locale);
    }

    private ProblemDetail problem(Map<String, String> errors, Locale locale) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage("problem.validationFailed", null, locale));
        detail.setType(VALIDATION_FAILED);
        detail.setProperty("errors", errors);
        return detail;
    }
}
