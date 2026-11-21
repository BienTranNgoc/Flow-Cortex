package com.flowcortex.app.clienterrors;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Turns a refusal into a problem whose fixed title, localized detail and {@code code} property hold
 * nothing the caller sent. Takes precedence over the global handler for this controller only.
 */
@RestControllerAdvice(assignableTypes = ClientErrorsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ClientErrorsProblemHandler {

    private static final URI PROBLEM_BASE = URI.create("https://flow-cortex.dev/problems/");
    private static final String TITLE = "Client error report refused";

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    ClientErrorsProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(ClientErrorRefusedException.class)
    ResponseEntity<ProblemDetail> handle(ClientErrorRefusedException refused, HttpServletRequest request) {
        ClientErrorRefusal refusal = refused.refusal();
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                refusal.status(), messages.getMessage(refusal.messageKey(), null, locale));
        problem.setTitle(TITLE);
        problem.setType(PROBLEM_BASE.resolve(refusal.code().replace('_', '-')));
        problem.setProperty("code", refusal.code());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(refusal.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (refused instanceof ClientErrorsRateLimitedException limited) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
        }
        return response.body(problem);
    }
}
