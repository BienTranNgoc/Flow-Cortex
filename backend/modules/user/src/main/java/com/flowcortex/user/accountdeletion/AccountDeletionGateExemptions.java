package com.flowcortex.user.accountdeletion;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The routes a pending account can still reach: the session, the sign-in and credential routes, its
 * own deletion routes, and the switches and error intake the app needs to draw the wall. The
 * credential routes are listed one by one, so {@code /api/v1/auth/profile} stays closed.
 */
final class AccountDeletionGateExemptions {

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final List<PathPattern> EXEMPT = Stream.of(
            "/api/v1/auth/me",
            "/api/v1/auth/logout",
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/confirm-email",
            "/api/v1/auth/resend-confirmation",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/oauth/google/**",
            "/api/v1/me/account-deletion",
            "/api/v1/me/account-deletion/**",
            "/api/v1/features",
            "/api/v1/client-errors")
            .map(PARSER::parse)
            .toList();

    private AccountDeletionGateExemptions() {
    }

    static boolean isExempt(String path) {
        return isExempt(PathContainer.parsePath(path));
    }

    static boolean isExempt(PathContainer path) {
        return EXEMPT.stream().anyMatch(pattern -> pattern.matches(path));
    }
}
