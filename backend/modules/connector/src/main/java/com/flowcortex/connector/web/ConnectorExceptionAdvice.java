package com.flowcortex.connector.web;

import com.flowcortex.connector.api.ConnectorException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Writes a {@link ConnectorException} of the connector routes as an RFC 9457 problem in the request's
 * language. Ordered first: the shared handler's catch-all would otherwise turn it into a 500.
 */
@RestControllerAdvice(basePackages = "com.flowcortex.connector.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
class ConnectorExceptionAdvice {

    private static final String PROBLEM_BASE = "https://flow-cortex.dev/problems/connector-";

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    ConnectorExceptionAdvice(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(ConnectorException.class)
    ResponseEntity<ProblemDetail> connector(ConnectorException ex, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        HttpStatus status = status(ex.reason());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, messages.getMessage(ex.detail(), locale));
        problem.setType(URI.create(PROBLEM_BASE + slug(ex.reason())));
        problem.setProperty("code", ex.reason().name());
        problem.setProperty("retryable", ex.retryable());
        if (ex.telegramReason() != null) {
            problem.setProperty("reason", ex.telegramReason());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (ex.reason() == ConnectorException.Reason.RATE_LIMITED && ex.retryAfter() != null) {
            long seconds = Math.max(1, (ex.retryAfter().toMillis() + 999) / 1000);
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }
        return response.body(problem);
    }

    static HttpStatus status(ConnectorException.Reason reason) {
        return switch (reason) {
            case INVALID_INPUT, OUT_OF_BASE_URL -> HttpStatus.BAD_REQUEST;
            case SUSPENDED -> HttpStatus.FORBIDDEN;
            case NOT_CONNECTED -> HttpStatus.NOT_FOUND;
            case USAGE_DISABLED, CREDENTIAL_UNREADABLE -> HttpStatus.CONFLICT;
            case REFUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case RATE_LIMITED, TURNED_OFF -> HttpStatus.SERVICE_UNAVAILABLE;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
        };
    }

    private static String slug(ConnectorException.Reason reason) {
        return reason.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
