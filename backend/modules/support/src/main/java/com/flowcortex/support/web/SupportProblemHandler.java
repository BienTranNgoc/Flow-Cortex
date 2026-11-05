package com.flowcortex.support.web;

import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ordered first: the shared handler's catch-all would otherwise turn a support refusal into a 500. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SupportProblemHandler {

    private static final URI PROBLEM_BASE = URI.create("https://flow-cortex.dev/problems/");

    @ExceptionHandler(SupportProblemException.class)
    ProblemDetail problem(SupportProblemException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        detail.setType(PROBLEM_BASE.resolve(e.code()));
        detail.setProperty("code", e.code());
        e.properties().forEach(detail::setProperty);
        return detail;
    }
}
