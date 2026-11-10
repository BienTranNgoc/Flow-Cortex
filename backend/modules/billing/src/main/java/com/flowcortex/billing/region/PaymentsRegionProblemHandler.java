package com.flowcortex.billing.region;

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

/** Answers a region refusal with 403 and its code; ordered first because the shared catch-all would turn it into a 500. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class PaymentsRegionProblemHandler {

    private static final URI TYPE = URI.create("https://flow-cortex.dev/problems/payments-region-unavailable");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    PaymentsRegionProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(PaymentsRegionUnavailableException.class)
    ProblemDetail unavailable(PaymentsRegionUnavailableException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(TYPE);
        detail.setProperty("code", PaymentsRegionUnavailableException.CODE);
        return detail;
    }
}
