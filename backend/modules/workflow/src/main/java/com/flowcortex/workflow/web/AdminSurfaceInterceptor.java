package com.flowcortex.workflow.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The admin kill switch: with {@code flowcortex.admin.enabled} off, every {@code /api/v1/admin/**}
 * request is refused with 503 Problem Detail. It runs after the security chain, so the role
 * checks still answer first. The flag binds at startup; flipping it needs a restart.
 */
@Component
class AdminSurfaceInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final boolean adminEnabled;

    AdminSurfaceInterceptor(@Value("${flowcortex.admin.enabled:true}") boolean adminEnabled) {
        this.adminEnabled = adminEnabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/admin/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!adminEnabled) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("admin.disabled"));
        }
        return true;
    }
}
