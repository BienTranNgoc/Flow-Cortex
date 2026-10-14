package com.flowcortex.user.web;

import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.api.OAuthFailure;
import com.flowcortex.user.api.OAuthLoginException;
import com.flowcortex.user.application.GoogleOAuthClient;
import com.flowcortex.user.application.GoogleOAuthProperties;
import com.flowcortex.user.application.OAuthFlowCookieCodec;
import com.flowcortex.user.application.OAuthFlowState;
import com.flowcortex.user.application.SafeReturnTo;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Google OAuth 2.0 authorization-code flow with PKCE.
 *
 * <p>{@code GET /authorize} seals the attempt (state, PKCE verifier, nonce, validated return path)
 * into an HttpOnly cookie and redirects to Google. {@code GET /callback} only proceeds when that
 * cookie, set by this same browser, matches the {@code state} Google returns. It never puts a token
 * in a URL: success sets the refresh cookie and redirects to the return path, where the frontend
 * obtains its access token from {@code /refresh}; every failure redirects to
 * {@code /login?oauth_error=<code>}.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth/google")
class OAuthController {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthController.class);

    static final String FLOW_COOKIE = "oauth_flow";

    static final String FLOW_COOKIE_PATH = "/api/v1/auth/oauth/google";

    private static final String LOGIN_ERROR_PATH = "/login?oauth_error=";

    private final GoogleOAuthProperties properties;
    private final GoogleOAuthClient googleClient;
    private final OAuthFlowCookieCodec flowCodec;
    private final AuthService authService;
    private final RefreshCookieSupport cookieSupport;
    private final Clock clock;

    OAuthController(GoogleOAuthProperties properties, GoogleOAuthClient googleClient, OAuthFlowCookieCodec flowCodec,
            AuthService authService, RefreshCookieSupport cookieSupport, Clock clock) {
        this.clock = clock;
        this.properties = properties;
        this.googleClient = googleClient;
        this.flowCodec = flowCodec;
        this.authService = authService;
        this.cookieSupport = cookieSupport;
    }

    @SecurityRequirements
    @ApiResponse(responseCode = "302", description = "Redirect to Google's consent screen")
    @ApiResponse(responseCode = "503", description = "Google sign-in is not configured")
    @GetMapping("/authorize")
    void authorize(@RequestParam(name = "remember_me", defaultValue = "false") boolean rememberMe,
            @RequestParam(name = "return_to", required = false) String returnTo, HttpServletResponse response) {
        if (!googleClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google sign-in is not configured");
        }
        OAuthFlowState flow = OAuthFlowState.begin(SafeReturnTo.of(returnTo), rememberMe, properties.stateTtl(),
                clock);
        response.addHeader(HttpHeaders.SET_COOKIE, flowCookie(flowCodec.seal(flow))
                .maxAge(properties.stateTtl()).build().toString());
        redirect(response, googleClient.buildAuthorizeUrl(flow));
    }

    @SecurityRequirements
    @ApiResponse(responseCode = "302",
            description = "Signed in: refresh cookie set, redirect to the return path. Refused: redirect to"
                    + " /login?oauth_error=<cancelled|invalid_state|registration_closed|link_required"
                    + "|account_inactive|rate_limited|failed>")
    @GetMapping("/callback")
    void callback(@RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String googleError,
            HttpServletRequest request, HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        response.addHeader(HttpHeaders.SET_COOKIE, flowCookie("").maxAge(0).build().toString());

        Optional<OAuthFlowState> flow = readFlowCookie(request).flatMap(flowCodec::open)
                .filter(candidate -> sameState(candidate.state(), state));
        if (flow.isEmpty()) {
            refuse(OAuthFailure.INVALID_STATE, ip, userAgent, response);
            return;
        }
        if (googleError != null) {
            refuse("access_denied".equals(googleError) ? OAuthFailure.CANCELLED : OAuthFailure.FAILED, ip, userAgent,
                    response);
            return;
        }
        if (code == null || code.isBlank()) {
            refuse(OAuthFailure.FAILED, ip, userAgent, response);
            return;
        }
        try {
            AuthSession session = authService.loginWithGoogle(code, flow.get().codeVerifier(), flow.get().nonce(),
                    flow.get().rememberMe(), ip, userAgent);
            cookieSupport.attachRefreshCookie(request, response, session);
            redirect(response, flow.get().returnTo());
        } catch (OAuthLoginException ex) {
            redirect(response, LOGIN_ERROR_PATH + ex.failure().code());
        } catch (RuntimeException ex) {
            LOG.error("Google sign-in failed unexpectedly", ex);
            refuse(OAuthFailure.FAILED, ip, userAgent, response);
        }
    }

    private void refuse(OAuthFailure failure, String ip, String userAgent, HttpServletResponse response) {
        authService.recordOAuthFailure(failure, ip, userAgent);
        redirect(response, LOGIN_ERROR_PATH + failure.code());
    }

    private static void redirect(HttpServletResponse response, String location) {
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, location);
    }

    private static Optional<String> readFlowCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (FLOW_COOKIE.equals(cookie.getName())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private static boolean sameState(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseCookie.ResponseCookieBuilder flowCookie(String value) {
        return ResponseCookie.from(FLOW_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(FLOW_COOKIE_PATH);
    }
}
