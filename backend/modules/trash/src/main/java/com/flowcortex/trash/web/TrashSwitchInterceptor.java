package com.flowcortex.trash.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.trash.application.TrashProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The Trash switch: with {@code flowcortex.trash.enabled} off, every {@code /api/v1/trash} request is refused with
 * 503 before Spring reads the body or converts a parameter, so no binding error can answer in its place. It runs
 * after the security chain, so an unauthenticated call still answers 401 first.
 */
@Component
class TrashSwitchInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final TrashProperties properties;

    TrashSwitchInterceptor(TrashProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/trash", "/api/v1/trash/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.enabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"));
        }
        return true;
    }
}
