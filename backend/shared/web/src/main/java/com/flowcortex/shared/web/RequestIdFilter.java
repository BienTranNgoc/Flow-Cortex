package com.flowcortex.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a request id on every log line so the lines belonging to one request
 * can be pulled out of the pile (design.md D3/R-009).
 *
 * <p>Reuses an inbound {@code X-Request-Id} when the caller supplied one,
 * otherwise mints one; the id is echoed back on the response so a client can
 * quote it when reporting a problem. Runs first so the id is present for
 * anything later in the chain, including an authentication failure. The MDC
 * is cleared in a {@code finally} block: servlet containers pool threads, and
 * a leaked id would mislabel the next, unrelated request.
 *
 * <p>Emits one structured request-summary log line per request, correlated by
 * the same id. The line carries only method/path/status/duration — never a
 * header value or the request/response body, so no secret or password can
 * leak through it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** Header carrying a caller-supplied id, and the one echoed back. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MDC key for the correlation id. */
    public static final String MDC_KEY = "requestId";

    /** Bounds what a caller can write into the logs. */
    private static final int MAX_LENGTH = 64;

    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long startedAtMillis = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            LOG.info("request requestId={} method={} path={} status={} durationMs={}",
                    requestId, request.getMethod(), request.getRequestURI(), response.getStatus(),
                    System.currentTimeMillis() - startedAtMillis);
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accepts only short, printable, non-whitespace ids. An inbound header is
     * attacker-controlled and lands in the logs, so anything that could forge
     * a log line — newlines above all — is rejected and replaced by a fresh
     * id.
     */
    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                return null;
            }
        }
        return trimmed;
    }
}
