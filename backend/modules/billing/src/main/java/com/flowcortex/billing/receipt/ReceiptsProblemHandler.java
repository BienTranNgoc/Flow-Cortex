package com.flowcortex.billing.receipt;

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

/** Answers a receipt the member cannot have with 404 or 409 and its code; the shared answers carry no code. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReceiptsProblemHandler {

    private static final URI NOT_FOUND_TYPE = URI.create("https://flow-cortex.dev/problems/billing-receipt-not-found");
    private static final URI NOT_COLLECTED_TYPE =
            URI.create("https://flow-cortex.dev/problems/billing-receipt-not-collected");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    ReceiptsProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(ReceiptNotFoundException.class)
    ProblemDetail notFound(ReceiptNotFoundException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(NOT_FOUND_TYPE);
        detail.setProperty("code", ReceiptNotFoundException.CODE);
        return detail;
    }

    @ExceptionHandler(ReceiptNotCollectedException.class)
    ProblemDetail notCollected(ReceiptNotCollectedException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(NOT_COLLECTED_TYPE);
        detail.setProperty("code", ReceiptNotCollectedException.CODE);
        return detail;
    }
}
