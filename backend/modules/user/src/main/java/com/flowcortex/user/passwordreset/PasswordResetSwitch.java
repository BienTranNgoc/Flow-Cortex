package com.flowcortex.user.passwordreset;

import com.flowcortex.settings.api.Settings;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * With the {@link PasswordResetSettings#ENABLED} switch off, both reset endpoints are refused with 503
 * before their body is read. The switch is read per request, so a change applies without a restart.
 */
@Component
class PasswordResetSwitch implements HandlerInterceptor, WebMvcConfigurer {

    private final ObjectProvider<Settings> settings;

    /** A web slice test loads every {@code WebMvcConfigurer} but not the settings registry; no reset route runs there. */
    PasswordResetSwitch(ObjectProvider<Settings> settings) {
        this.settings = settings;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/auth/forgot-password", "/api/v1/auth/reset-password");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Settings registry = settings.getIfAvailable();
        if (registry == null || !registry.get(PasswordResetSettings.ENABLED).effective()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"));
        }
        return true;
    }
}
