package com.flowcortex.app.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Returns the request's trace id as {@code X-Trace-Id}, so a caller can find the trace a request
 * started. Ordered inside the server observation filter, which is what opens the request's span.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class TraceIdResponseFilter extends OncePerRequestFilter {

    static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final Tracer tracer;

    /** Without a tracer (tracing switched off, a web slice test) no header is added. */
    TraceIdResponseFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null) {
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        }
        filterChain.doFilter(request, response);
    }
}
