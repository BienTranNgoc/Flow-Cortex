package com.flowcortex.user.web;

import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Sets and clears the {@code refresh_token} cookie for the remember-me
 * flow. Lives in the web layer because the cookie is a transport detail,
 * not a domain concern.
 */
@Component
class RefreshCookieSupport {

    ResponseCookie refreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(AuthController.REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(AuthController.REFRESH_COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }

    void attachRefreshCookie(ResponseEntity.BodyBuilder builder, String value, long maxAgeSeconds) {
        builder.header("Set-Cookie", refreshCookie(value, maxAgeSeconds).toString());
    }

    void attachRefreshCookie(jakarta.servlet.http.HttpServletResponse response, String value) {
        // 30 days matches RefreshTokenProperties default rememberMeTtl.
        long maxAge = java.time.Duration.ofDays(30).toSeconds();
        response.addHeader("Set-Cookie", refreshCookie(value, maxAge).toString());
    }

    void clearRefreshCookie(jakarta.servlet.http.HttpServletResponse response) {
        ResponseCookie cleared = ResponseCookie.from(AuthController.REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(AuthController.REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cleared.toString());
    }
}
