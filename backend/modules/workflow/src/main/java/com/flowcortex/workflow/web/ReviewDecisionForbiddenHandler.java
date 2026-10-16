package com.flowcortex.workflow.web;

import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A refused review decision tells the member why, so the 403 carries the service's sentence. The
 * shared handler answers every {@link AccessDeniedException} with one generic sentence; ordered
 * first so this one wins for the review endpoints only.
 */
@RestControllerAdvice(assignableTypes = ReviewTasksController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReviewDecisionForbiddenHandler {

    private static final URI FORBIDDEN = URI.create("https://flow-cortex.dev/problems/forbidden");

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        detail.setType(FORBIDDEN);
        return detail;
    }
}
