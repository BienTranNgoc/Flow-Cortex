package com.flowcortex.user.emailconfirm;

import java.net.URI;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Problem details for registration and confirmation refusals, in the shape the shared
 * {@code GlobalExceptionHandler} gives every other problem plus a {@code code} or field {@code errors}.
 * Ordered first because the shared handler's catch-all would otherwise claim these exceptions.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class RegistrationProblemHandler {

    private static final URI PROBLEM_BASE = URI.create("https://flow-cortex.dev/problems/");

    @ExceptionHandler(EmailNotConfirmedException.class)
    ProblemDetail handleEmailNotConfirmed(EmailNotConfirmedException ex) {
        ProblemDetail detail = problem(HttpStatus.FORBIDDEN, "forbidden", ex.getMessage());
        detail.setProperty("code", EmailNotConfirmedException.CODE);
        return detail;
    }

    @ExceptionHandler(RegistrationConflictException.class)
    ProblemDetail handleRegistrationConflict(RegistrationConflictException ex) {
        ProblemDetail detail = problem(HttpStatus.CONFLICT, "conflict", ex.getMessage());
        detail.setProperty("errors", Map.of(ex.getField(), ex.getMessage()));
        return detail;
    }

    private static ProblemDetail problem(HttpStatus status, String typeSlug, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setType(PROBLEM_BASE.resolve(typeSlug));
        return detail;
    }
}
