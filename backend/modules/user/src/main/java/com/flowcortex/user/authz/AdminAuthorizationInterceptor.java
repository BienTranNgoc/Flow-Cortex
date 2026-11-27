package com.flowcortex.user.authz;

import com.flowcortex.user.application.JwtClaims;
import com.flowcortex.user.totp.TotpRequirement;
import com.flowcortex.user.totp.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Decides every governed handler by its declared posture and the caller's facts read on this request,
 * so a role, key or deactivation change applies to the next call made with an already-issued token.
 * Runs before every other interceptor, the admin kill switch included, so a caller without the key
 * is refused before it learns whether the surface is on.
 */
class AdminAuthorizationInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final CallerFactsLoader factsLoader;
    /** Providers, not beans: a controller slice test registers this interceptor without the TOTP beans. */
    private final ObjectProvider<TotpRequirement> requirement;
    private final ObjectProvider<TotpService> totp;

    AdminAuthorizationInterceptor(CallerFactsLoader factsLoader, ObjectProvider<TotpRequirement> requirement,
            ObjectProvider<TotpService> totp) {
        this.factsLoader = factsLoader;
        this.requirement = requirement;
        this.totp = totp;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/**").order(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Posture posture = PostureResolver.of(method.getMethod());
        if (posture instanceof Posture.None && !GovernedPaths.isGoverned(matchedPattern(request))) {
            return true;
        }
        if (posture instanceof Posture.Public) {
            return true;
        }
        if (!needsCaller(posture)) {
            AdminAuthorizationDecision.decide(posture, noCaller(), false).requireAllowed(() -> false);
            return true;
        }
        JwtAuthenticationToken token = signedInToken();
        UUID callerId = subjectOf(token);
        CallerFacts facts = factsLoader.load(callerId, JwtClaims.mfaOf(token.getToken()));
        AdminAuthorizationDecision.decide(posture, facts, requirement.getObject().adminRequired())
                .requireAllowed(() -> totp.getObject().isEnabled(callerId));
        return true;
    }

    /** None and Conflict are refused whoever calls, so no row is read for them. */
    private static boolean needsCaller(Posture posture) {
        return !(posture instanceof Posture.None || posture instanceof Posture.Conflict);
    }

    private static CallerFacts noCaller() {
        return new CallerFacts(false, false, false, false, Set.of());
    }

    private static String matchedPattern(HttpServletRequest request) {
        return (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    }

    /** 401 when no signed-in caller reached the handler; the filter chains normally refuse first. */
    private static JwtAuthenticationToken signedInToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            return token;
        }
        throw new InsufficientAuthenticationException("Authentication is required");
    }

    private static UUID subjectOf(JwtAuthenticationToken token) {
        try {
            return UUID.fromString(token.getToken().getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InsufficientAuthenticationException("Token subject is not a user id", e);
        }
    }
}
