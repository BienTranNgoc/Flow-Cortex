package com.flowcortex.billing.profile;

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

/** Answers a profile the member cannot read with 404; the shared catch-all would turn it into a 500. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class BillingProfilesProblemHandler {

    private static final URI NOT_FOUND_TYPE = URI.create("https://flow-cortex.dev/problems/billing-profile-not-found");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    BillingProfilesProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(BillingProfileNotFoundException.class)
    ProblemDetail notFound(BillingProfileNotFoundException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(NOT_FOUND_TYPE);
        detail.setProperty("code", BillingProfileNotFoundException.CODE);
        return detail;
    }
}
