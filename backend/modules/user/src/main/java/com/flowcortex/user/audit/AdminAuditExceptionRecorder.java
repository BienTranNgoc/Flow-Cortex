package com.flowcortex.user.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Remembers the exception of an audited request, then declines to resolve it so the real exception
 * handler still answers. Without it {@code afterCompletion} cannot see an exception that was
 * already turned into a response.
 */
@Component
class AdminAuditExceptionRecorder implements HandlerExceptionResolver, Ordered {

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        if (request.getAttribute(PendingAudit.ATTRIBUTE) instanceof PendingAudit pending) {
            pending.exception(ex);
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
