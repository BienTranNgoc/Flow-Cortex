package com.flowcortex.user.phone;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Refuses a signed-in caller the phone gate holds with 403, before the handler runs, on every
 * {@code /api/v1/} route except {@link PhoneGateExemptions}. Runs after the security chain and the
 * admin authorization interceptor, so 401 and a missing admin key still answer first; a request with
 * no signed-in caller is never refused here.
 */
class PhoneGateInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final PhoneGate gate;
    private final PhoneTelemetry telemetry;

    PhoneGateInterceptor(PhoneGate gate, PhoneTelemetry telemetry) {
        this.gate = gate;
        this.telemetry = telemetry;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == DispatcherType.ERROR || PhoneGateExemptions.isExempt(pathOf(request))) {
            return true;
        }
        Optional<UUID> caller = callerId();
        if (caller.isPresent() && gate.holds(caller.get())) {
            telemetry.gateBlocked();
            throw new PhoneVerificationRequiredException();
        }
        return true;
    }

    /** The path Spring matched the handler on, so an encoded or parameterised variant cannot dodge the list. */
    private static PathContainer pathOf(HttpServletRequest request) {
        RequestPath path = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : ServletRequestPathUtils.parseAndCache(request);
        return path.pathWithinApplication();
    }

    private static Optional<UUID> callerId() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && token.isAuthenticated()) {
            try {
                return Optional.of(UUID.fromString(token.getToken().getSubject()));
            } catch (IllegalArgumentException | NullPointerException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
