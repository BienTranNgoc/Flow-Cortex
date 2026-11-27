package com.flowcortex.billing.history;

import com.flowcortex.billing.terms.TermsUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Answers a purchase the member cannot read with 404 and a purchase whose terms cannot be served with 503;
 * ordered first because the shared catch-all would turn them into a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AgreementsProblemHandler {

    static final String UNAVAILABLE_CODE = "payment_agreement_terms_unavailable";

    private static final Logger LOG = LoggerFactory.getLogger(AgreementsProblemHandler.class);
    private static final URI NOT_FOUND_TYPE = URI.create("https://flow-cortex.dev/problems/payment-agreement-not-found");
    private static final URI UNAVAILABLE_TYPE =
            URI.create("https://flow-cortex.dev/problems/payment-agreement-terms-unavailable");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    AgreementsProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(AgreementNotFoundException.class)
    ProblemDetail notFound(AgreementNotFoundException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(NOT_FOUND_TYPE);
        detail.setProperty("code", AgreementNotFoundException.CODE);
        return detail;
    }

    @ExceptionHandler(TermsUnavailableException.class)
    ProblemDetail termsUnavailable(TermsUnavailableException e, HttpServletRequest request) {
        LOG.error("Payment terms cannot be served: event=billing.agreement.terms_unavailable slug=legal-payment");
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(UNAVAILABLE_TYPE);
        detail.setProperty("code", UNAVAILABLE_CODE);
        return detail;
    }
}
