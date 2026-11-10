package com.flowcortex.user.pat;

import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import com.flowcortex.shared.security.PersonalAccessTokenAuthenticator;
import com.flowcortex.user.application.OpaqueTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Counts every request whose bearer starts with {@code fc_pat_} against that token value's budget,
 * valid or not, and answers 429 before the security chain authenticates it. The limiters are keyed
 * on the SHA-256 of the token, never the token itself. Requests with any other credential pass untouched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class PatRateLimitFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final RateLimiter perMinute;
    private final RateLimiter perDay;
    private final HandlerExceptionResolver exceptionResolver;

    PatRateLimitFilter(RateLimiters rateLimiters,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.perMinute = rateLimiters.named(PatRateLimits.PAT_TOKEN_MINUTE);
        this.perDay = rateLimiters.named(PatRateLimits.PAT_TOKEN_DAY);
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return patToken(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String identity = OpaqueTokens.hash(patToken(request));
        Optional<Duration> wait = perMinute.tryAcquire(identity);
        if (wait.isEmpty()) {
            wait = perDay.tryAcquire(identity);
        }
        if (wait.isPresent()) {
            exceptionResolver.resolveException(request, response, null, new PatRateLimitedException(wait.get()));
            return;
        }
        chain.doFilter(request, response);
    }

    private static String patToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.startsWith(PersonalAccessTokenAuthenticator.PREFIX) ? token : null;
    }
}
