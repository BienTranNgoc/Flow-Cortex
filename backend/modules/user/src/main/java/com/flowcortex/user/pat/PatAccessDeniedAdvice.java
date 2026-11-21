package com.flowcortex.user.pat;

import com.flowcortex.shared.security.PersonalAccessTokenAuthenticator;
import com.flowcortex.shared.web.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * A token principal has no role, so a route guarded by a role (the admin chain) denies it before the
 * route interceptor runs. This answers that denial as {@code PAT_NOT_ALLOWED}, like every other
 * route outside the allowlist; any other denial is rendered exactly as the global handler does.
 *
 * <p>It also claims {@link AuthenticationException}: Spring picks the first advice that matches an
 * exception or, failing a direct match, its cause, and an anonymous denial arrives as an
 * authentication exception caused by an {@link AccessDeniedException}, which must stay a 401.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class PatAccessDeniedAdvice {

    private final GlobalExceptionHandler global;

    PatAccessDeniedAdvice(GlobalExceptionHandler global) {
        this.global = global;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) throws Exception {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && PersonalAccessTokenAuthenticator.isPat(jwt)) {
            return global.handleException(new PatNotAllowedException(), new ServletWebRequest(request));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(global.handleAccessDenied(ex, request));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<?> handleUnauthenticated(AuthenticationException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(global.handleUnauthenticated(ex, request));
    }
}
