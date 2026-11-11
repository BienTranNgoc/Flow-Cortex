package com.flowcortex.content.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Ordered first: the shared handler's catch-all would otherwise turn a content refusal into a 500. Because it
 * runs ahead of the shared handler, it puts the detail and the field errors into the request's language itself.
 * A 404 on the public routes is never cacheable, so a read made before a publication is not reused after it.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ContentProblemHandler {

    static final String PUBLIC_PREFIX = "/api/v1/content/";

    private static final URI PROBLEM_BASE = URI.create("https://flow-cortex.dev/problems/");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    ContentProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(ContentProblemException.class)
    ProblemDetail problem(ContentProblemException e, HttpServletRequest request, HttpServletResponse response) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(e.status(), messages.getMessage(e.detail(), locale));
        detail.setType(PROBLEM_BASE.resolve(e.code()));
        detail.setProperty("code", e.code());
        e.properties().forEach(detail::setProperty);
        if (!e.fieldErrors().isEmpty()) {
            Map<String, String> errors = new LinkedHashMap<>();
            e.fieldErrors().forEach((path, key) -> errors.put(path, messages.getMessage(key, null, locale)));
            detail.setProperty("errors", errors);
        }
        if (e.status() == HttpStatus.NOT_FOUND && request.getRequestURI().startsWith(PUBLIC_PREFIX)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        return detail;
    }
}
