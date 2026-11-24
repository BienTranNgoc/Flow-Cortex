package com.flowcortex.user.oauth;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * With {@code flowcortex.auth.mcp-oauth.enabled} off, every route of the authorization server answers
 * 503 before its handler runs, so nothing is read or stored. The token and revocation routes keep
 * their no-store contract on that answer, which the global problem handler does not set.
 */
class McpOAuthSwitch implements HandlerInterceptor, WebMvcConfigurer {

    static final String METADATA_PATH = "/.well-known/oauth-authorization-server";
    static final String TOKEN_PATH = "/api/v1/oauth/token";
    static final String REVOKE_PATH = "/api/v1/oauth/revoke";

    private final McpOAuthProperties properties;

    McpOAuthSwitch(McpOAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/oauth/**", METADATA_PATH);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.enabled()) {
            String path = request.getRequestURI();
            if (TOKEN_PATH.equals(path) || REVOKE_PATH.equals(path)) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
                response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            }
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"));
        }
        return true;
    }
}
