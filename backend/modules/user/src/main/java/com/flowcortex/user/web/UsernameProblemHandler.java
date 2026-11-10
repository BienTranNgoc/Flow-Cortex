package com.flowcortex.user.web;

import com.flowcortex.user.profile.UsernameChangeTooSoonException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Answers the username cooldown refusal with the unlock date written in the request's language, plus
 * {@code nextChangeAt}. It handles this one type only, so every other 422 stays with the shared
 * handler; it is ordered first because that handler would otherwise claim this subclass too.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UsernameProblemHandler {

    private static final URI TYPE = URI.create("https://flow-cortex.dev/problems/unprocessable");

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    UsernameProblemHandler(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(UsernameChangeTooSoonException.class)
    ProblemDetail handleTooSoon(UsernameChangeTooSoonException ex, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        String date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                .withZone(ZoneOffset.UTC).format(ex.getNextChangeAt());
        String message = messages.getMessage(UsernameChangeTooSoonException.MESSAGE_KEY, new Object[] {date},
                locale);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, message);
        detail.setType(TYPE);
        detail.setProperty("code", UsernameChangeTooSoonException.CODE);
        detail.setProperty("errors", Map.of("username", message));
        detail.setProperty("nextChangeAt", ex.getNextChangeAt().toString());
        return detail;
    }
}
