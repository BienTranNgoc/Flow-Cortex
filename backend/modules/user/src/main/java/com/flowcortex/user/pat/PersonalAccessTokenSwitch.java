package com.flowcortex.user.pat;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * With {@code flowcortex.auth.personal-access-tokens.enabled} off, the token routes answer 503 before
 * their body is read. The flag binds at startup; the authenticator refuses the tokens themselves.
 */
class PersonalAccessTokenSwitch implements HandlerInterceptor, WebMvcConfigurer {

    private final PersonalAccessTokenProperties properties;

    PersonalAccessTokenSwitch(PersonalAccessTokenProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/me/api-tokens", "/api/v1/me/api-tokens/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.enabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"));
        }
        return true;
    }
}
