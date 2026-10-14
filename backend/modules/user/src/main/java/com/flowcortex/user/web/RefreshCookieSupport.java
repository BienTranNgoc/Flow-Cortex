package com.flowcortex.user.web;

import com.flowcortex.user.api.AuthSession;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Sets, reads and clears the refresh cookie. Lives in the web layer because the cookie is a
 * transport detail, not a domain concern. A remembered session gets a cookie that lives until
 * the session's real expiry; any other session gets a cookie that ends with the browser session.
 *
 * <p>The cookie name depends on the calling app: a request carrying {@code X-FlowCortex-Client:
 * admin} uses {@code refresh_token_admin}, every other request {@code refresh_token}. Apps served
 * from one host (different ports share cookies) therefore keep independent sessions.
 */
@Component
class RefreshCookieSupport {

    static final String CLIENT_HEADER = "X-FlowCortex-Client";

    static final String MEMBER_COOKIE = "refresh_token";

    static final String ADMIN_COOKIE = "refresh_token_admin";

    private static final String ADMIN_CLIENT = "admin";

    private static final String COOKIE_PATH = "/api/v1/auth";

    private final Clock clock;

    RefreshCookieSupport(Clock clock) {
        this.clock = clock;
    }

    void attachRefreshCookie(HttpServletRequest request, HttpServletResponse response, AuthSession session) {
        ResponseCookie.ResponseCookieBuilder cookie = baseCookie(cookieName(request), session.refreshToken());
        if (session.persistent()) {
            long remaining = Duration.between(Instant.now(clock), session.refreshTokenExpiresAt()).toSeconds();
            cookie.maxAge(Math.max(remaining, 0));
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }

    void clearRefreshCookie(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie(cookieName(request), "").maxAge(0).build().toString());
    }

    /** The raw refresh token the request carries under this app's cookie name, or {@code null}. */
    String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = cookieName(request);
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static String cookieName(HttpServletRequest request) {
        return ADMIN_CLIENT.equals(request.getHeader(CLIENT_HEADER)) ? ADMIN_COOKIE : MEMBER_COOKIE;
    }

    private static ResponseCookie.ResponseCookieBuilder baseCookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(COOKIE_PATH);
    }
}
