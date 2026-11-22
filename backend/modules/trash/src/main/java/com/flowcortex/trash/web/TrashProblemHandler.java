package com.flowcortex.trash.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.trash.application.TrashProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Ordered first: the shared handler's catch-all would otherwise turn a rule violation into a 500. Because it runs
 * ahead of the shared handler, it puts the detail and the field errors into the request's language itself.
 */
@RestControllerAdvice(basePackages = "com.flowcortex.trash.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
class TrashProblemHandler {

    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");
    private static final URI CONFLICT = URI.create("https://flow-cortex.dev/problems/conflict");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;
    private final TrashProperties properties;

    TrashProblemHandler(MessageSource messages, LocaleResolver localeResolver, TrashProperties properties) {
        this.messages = messages;
        this.localeResolver = localeResolver;
        this.properties = properties;
    }

    @ExceptionHandler(TrashInvalidRequestException.class)
    ProblemDetail invalidRequest(TrashInvalidRequestException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage("problem.validationFailed", null, locale));
        detail.setType(VALIDATION_FAILED);
        detail.setProperty("errors", Map.of(e.field(), messages.getMessage(e.reason(), locale)));
        return detail;
    }

    @ExceptionHandler(TrashConflictException.class)
    ProblemDetail conflict(TrashConflictException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                messages.getMessage(e.messageKey(), null, e.messageKey(), locale));
        detail.setType(CONFLICT);
        return detail;
    }

    /**
     * A body that is missing, is not JSON or has a field of the wrong type is read before the controller runs, so
     * the switch check and the rule check never see it: this answers what they would, 503 while the Trash is off,
     * otherwise 400 on the field the route's body carries ({@code next} for empty, {@code items} for the others).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        if (!properties.enabled()) {
            return new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"))
                    .updateAndGetBody(messages, localeResolver.resolveLocale(request));
        }
        String field = request.getRequestURI().endsWith("/empty") ? "next" : "items";
        return invalidRequest(new TrashInvalidRequestException(field), request);
    }

    /** A whole-number parameter such as {@code page} or {@code size} that is not a number names itself like any bad value. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail unreadableParameter(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return invalidRequest(new TrashInvalidRequestException(e.getName()), request);
    }
}
