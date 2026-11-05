package com.flowcortex.support.web;

import com.flowcortex.support.config.SupportProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The Help Center kill switches on the member and guest routes ({@code /api/v1/support/**}): off
 * answers 503 {@code support-disabled}, and with the assistant switch off a question answers 503
 * {@code assistant-disabled} while the conversation history stays readable, as under the console
 * switch. The admin routes live elsewhere and stay up. Runs before the security chain, so a
 * switched-off route answers 503 whoever calls it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SupportSwitchFilter extends OncePerRequestFilter {

    private static final RequestMatcher SUPPORT = PathPatternRequestMatcher.withDefaults()
            .matcher("/api/v1/support/**");
    private static final RequestMatcher QUESTION = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/support/assistant/messages"),
            PathPatternRequestMatcher.withDefaults()
                    .matcher(HttpMethod.POST, "/api/v1/support/assistant/guest/messages"));

    private final SupportProperties properties;
    private final HandlerExceptionResolver exceptionResolver;

    SupportSwitchFilter(SupportProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.properties = properties;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SUPPORT.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            refuse(request, response, "support-disabled", "The Help Center is switched off");
            return;
        }
        if (!properties.assistant().enabled() && QUESTION.matches(request)) {
            refuse(request, response, "assistant-disabled", "The assistant is switched off");
            return;
        }
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String code, String detail) {
        exceptionResolver.resolveException(request, response, null,
                new SupportProblemException(HttpStatus.SERVICE_UNAVAILABLE, code, detail));
    }
}
