package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.ReviewDecisionForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/**
 * A refused review decision tells the member why, so the 403 carries the service's reason. The
 * shared handler answers every other access refusal with one generic sentence; ordered first so
 * this one wins for the review endpoints only.
 */
@RestControllerAdvice(assignableTypes = ReviewTasksController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReviewDecisionForbiddenHandler {

    private static final URI FORBIDDEN = URI.create("https://flow-cortex.dev/problems/forbidden");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    ReviewDecisionForbiddenHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(ReviewDecisionForbiddenException.class)
    ProblemDetail forbidden(ReviewDecisionForbiddenException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(FORBIDDEN);
        return detail;
    }
}
