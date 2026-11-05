package com.flowcortex.user.passwordreset;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * With {@code flowcortex.auth.password-reset.enabled} off, both reset endpoints are refused with 503
 * before their body is read. The flag binds at startup.
 */
@Component
class PasswordResetSwitch implements HandlerInterceptor, WebMvcConfigurer {

    private final boolean enabled;

    PasswordResetSwitch(@Value("${flowcortex.auth.password-reset.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/auth/forgot-password", "/api/v1/auth/reset-password");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "This feature is turned off");
        }
        return true;
    }
}
