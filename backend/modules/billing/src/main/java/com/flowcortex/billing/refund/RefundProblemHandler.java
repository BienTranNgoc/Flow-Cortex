package com.flowcortex.billing.refund;

import com.flowcortex.billing.refund.RefundResponses.TurnsView;
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
 * Answers a payment the member cannot read with 404 and a refused request or cancel with 409; ordered first
 * because the shared catch-all would turn them into a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class RefundProblemHandler {

    private static final String PROBLEMS = "https://flow-cortex.dev/problems/";
    private static final String TURN_LIMIT = "turn_limit_reached";

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    RefundProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(RefundTransactionNotFoundException.class)
    ProblemDetail notFound(RefundTransactionNotFoundException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                messages.getMessage(e.getDetail(), localeResolver.resolveLocale(request)));
        detail.setType(URI.create(PROBLEMS + "transaction-not-found"));
        detail.setProperty("code", RefundTransactionNotFoundException.CODE);
        return detail;
    }

    @ExceptionHandler(RefundNotEligibleException.class)
    ProblemDetail notEligible(RefundNotEligibleException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                messages.getMessage("billing.refundNotEligible", null, localeResolver.resolveLocale(request)));
        detail.setType(URI.create(PROBLEMS + "refund-not-eligible"));
        detail.setProperty("code", RefundNotEligibleException.CODE);
        detail.setProperty("refusal", e.right().refusal());
        if (TURN_LIMIT.equals(e.right().refusal())) {
            TurnsView turns = e.turns();
            detail.setProperty("limit", turns.limit());
            detail.setProperty("windowMonths", turns.windowMonths());
            detail.setProperty("nextTurnAt", turns.nextTurnAt() == null ? null : turns.nextTurnAt().toString());
        }
        return detail;
    }

    @ExceptionHandler(RefundRequestNotCancellableException.class)
    ProblemDetail notCancellable(RefundRequestNotCancellableException e, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                messages.getMessage("billing.refundRequestNotCancellable", null,
                        localeResolver.resolveLocale(request)));
        detail.setType(URI.create(PROBLEMS + "refund-request-not-cancellable"));
        detail.setProperty("code", RefundRequestNotCancellableException.CODE);
        return detail;
    }
}
