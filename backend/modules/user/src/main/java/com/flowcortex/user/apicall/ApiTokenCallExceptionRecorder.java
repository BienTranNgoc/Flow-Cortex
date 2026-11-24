package com.flowcortex.user.apicall;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Remembers the exception of a recorded token write, then declines to resolve it so the real
 * exception handler still answers; the filter reads it once the response is complete.
 */
@Component
class ApiTokenCallExceptionRecorder implements HandlerExceptionResolver, Ordered {

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        if (request.getAttribute(PendingCall.ATTRIBUTE) instanceof PendingCall pending) {
            pending.exception(ex);
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
