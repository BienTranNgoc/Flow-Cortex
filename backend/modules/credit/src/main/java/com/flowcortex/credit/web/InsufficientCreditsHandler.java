package com.flowcortex.credit.web;

import com.flowcortex.credit.api.InsufficientCreditsException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A start the member cannot pay for answers 402 with the numbers the form shows. Ordered first:
 * the shared handler's catch-all would otherwise turn it into a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class InsufficientCreditsHandler {

    private static final URI INSUFFICIENT_CREDITS = URI.create("https://flow-cortex.dev/problems/insufficient-credits");

    @ExceptionHandler(InsufficientCreditsException.class)
    ProblemDetail insufficient(InsufficientCreditsException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
        detail.setType(INSUFFICIENT_CREDITS);
        detail.setProperty("code", InsufficientCreditsException.CODE);
        detail.setProperty("balance", e.balance());
        detail.setProperty("required", e.required());
        detail.setProperty("shortfall", e.shortfall());
        return detail;
    }
}
