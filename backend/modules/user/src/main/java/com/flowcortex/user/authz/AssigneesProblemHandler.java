package com.flowcortex.user.authz;

import com.flowcortex.shared.web.LocalizedMessage;
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
 * Ordered first, as the shared handler's catch-all would turn the refusal into a 500; it writes the
 * validation-failed shape (type, {@code errors} by field) in the request's language itself.
 */
@RestControllerAdvice(assignableTypes = AdminAssigneesController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class AssigneesProblemHandler {

    private static final URI VALIDATION_FAILED = URI.create("https://flow-cortex.dev/problems/validation-failed");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    AssigneesProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(InvalidAssigneeListException.class)
    ProblemDetail invalidList(InvalidAssigneeListException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage(new LocalizedMessage("problem.validationFailed"), locale));
        detail.setType(VALIDATION_FAILED);
        detail.setProperty("errors", Map.of("for", messages.getMessage(e.reason(), locale)));
        return detail;
    }
}
