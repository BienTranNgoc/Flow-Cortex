package com.flowcortex.user.apicall;

import com.flowcortex.shared.web.LocalizedMessage;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/** Ordered first: the shared handler's catch-all would otherwise turn a call-guard refusal into a 500. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiTokenCallProblemHandler {

    private static final URI PROBLEM_BASE = URI.create("https://flow-cortex.dev/problems/");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    ApiTokenCallProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(ApiTokenCallProblemException.class)
    ProblemDetail problem(ApiTokenCallProblemException e, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(e.status(),
                messages.getMessage(e.localizedDetail(), locale));
        detail.setType(PROBLEM_BASE.resolve(e.code()));
        detail.setProperty("code", e.code());
        if (!e.fieldErrors().isEmpty()) {
            Map<String, String> errors = new LinkedHashMap<>();
            for (Map.Entry<String, LocalizedMessage> error : e.fieldErrors().entrySet()) {
                errors.put(error.getKey(), messages.getMessage(error.getValue(), locale));
            }
            detail.setProperty("errors", errors);
        }
        return detail;
    }
}
