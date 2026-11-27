package com.flowcortex.billing.tax;

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

/** Answers a refused rate change with 409 and its code; the shared conflict answer carries no code. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class VatRatesProblemHandler {

    private static final URI CONFLICT_TYPE = URI.create("https://flow-cortex.dev/problems/vat-rate-conflict");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    VatRatesProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(VatRateConflictException.class)
    ProblemDetail conflict(VatRateConflictException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(CONFLICT_TYPE);
        detail.setProperty("code", e.code());
        return detail;
    }
}
