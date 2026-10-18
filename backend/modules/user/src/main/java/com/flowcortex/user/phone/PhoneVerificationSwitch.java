package com.flowcortex.user.phone;

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
 * With {@code flowcortex.phone-verification.enabled} off, every {@code /api/v1/me/phone} request is
 * refused with 503 before its body is read. Runs after the security chain, so 401 still answers
 * first. The flag binds at startup.
 */
@Component
class PhoneVerificationSwitch implements HandlerInterceptor, WebMvcConfigurer {

    private final boolean enabled;

    PhoneVerificationSwitch(@Value("${flowcortex.phone-verification.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/me/phone", "/api/v1/me/phone/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"));
        }
        return true;
    }
}
