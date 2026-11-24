package com.flowcortex.billing.history;

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
 * Answers a payment the member cannot read with 404 and a refused query with 400; ordered first because the
 * shared catch-all would turn them into a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class TransactionsProblemHandler {

    private static final URI NOT_FOUND_TYPE = URI.create("https://flow-cortex.dev/problems/transaction-not-found");
    private static final URI INVALID_QUERY_TYPE =
            URI.create("https://flow-cortex.dev/problems/invalid-transaction-query");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    TransactionsProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    ProblemDetail notFound(TransactionNotFoundException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(NOT_FOUND_TYPE);
        detail.setProperty("code", TransactionNotFoundException.CODE);
        return detail;
    }

    @ExceptionHandler(InvalidTransactionQueryException.class)
    ProblemDetail invalidQuery(InvalidTransactionQueryException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(INVALID_QUERY_TYPE);
        detail.setProperty("code", e.code());
        return detail;
    }
}
