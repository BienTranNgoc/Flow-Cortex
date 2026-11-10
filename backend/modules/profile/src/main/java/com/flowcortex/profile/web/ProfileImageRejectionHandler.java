package com.flowcortex.profile.web;

import com.flowcortex.media.api.UploadRejectedException;
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
 * A refused profile upload renders like a field validation failure. The media port names a bad
 * image's field {@code uploadIds} (its plural form serves lists); these routes take one {@code uploadId}.
 * Ordered first: the shared handler's catch-all would otherwise turn it into a 500.
 */
@RestControllerAdvice(assignableTypes = ProfileImagesController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProfileImageRejectionHandler {

    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    ProfileImageRejectionHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(UploadRejectedException.class)
    ProblemDetail rejected(UploadRejectedException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        String field = "uploadIds".equals(e.field()) ? "uploadId" : e.field();
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage("problem.validationFailed", null, locale));
        detail.setType(VALIDATION_FAILED);
        detail.setProperty("errors", Map.of(field, messages.getMessage(e.detail(), locale)));
        return detail;
    }
}
