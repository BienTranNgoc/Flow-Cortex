package com.flowcortex.user.web;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.TokenPairResponse;
import com.flowcortex.user.application.GoogleOAuthClient;
import com.flowcortex.user.application.GoogleOAuthProperties;
import com.flowcortex.user.application.OAuthStateRecord;
import com.flowcortex.user.application.OAuthStateStore;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google OAuth 2.0 authorization-code flow.
 *
 * <p>{@code GET /authorize} generates a one-shot {@code state}, stores it
 * alongside the chosen {@code redirect_uri}, and 302s the browser to
 * Google. {@code GET /callback} consumes the state, exchanges the code for
 * an id_token, and forwards to the auth service for user resolution +
 * token issuance.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth/google")
class OAuthController {

    private static final List<String> SCOPES = List.of("openid", "email", "profile");

    private final GoogleOAuthProperties properties;
    private final GoogleOAuthClient googleClient;
    private final OAuthStateStore stateStore;
    private final AuthService authService;
    private final RefreshCookieSupport cookieSupport;

    OAuthController(GoogleOAuthProperties properties, GoogleOAuthClient googleClient, OAuthStateStore stateStore,
            AuthService authService, RefreshCookieSupport cookieSupport) {
        this.properties = properties;
        this.googleClient = googleClient;
        this.stateStore = stateStore;
        this.authService = authService;
        this.cookieSupport = cookieSupport;
    }

    @SecurityRequirements
    @GetMapping("/authorize")
    ResponseEntity<Void> authorize(@RequestParam("redirect_uri") String redirectUri, HttpServletRequest request) {
        if (properties.clientId() == null || properties.clientId().isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (properties.redirectUris() == null || !properties.redirectUris().contains(redirectUri)) {
            throw new ConflictException("redirect_uri is not in the allow-list");
        }
        OAuthStateRecord record = new OAuthStateRecord(redirectUri, Instant.now(), request.getRemoteAddr());
        String state = stateStore.put(record);
        String url = googleClient.buildAuthorizeUrl(state, redirectUri, SCOPES);
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url).build();
    }

    @SecurityRequirements
    @GetMapping("/callback")
    void callback(@RequestParam("code") String code, @RequestParam("state") String state,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "rememberMe", required = false) Boolean rememberMe,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean remember = rememberMe != null && rememberMe;
        TokenPairResponse pair = authService.loginWithGoogle(code, state, remember, request.getRemoteAddr(),
                request.getHeader(HttpHeaders.USER_AGENT));
        if (remember) {
            cookieSupport.attachRefreshCookie(response, pair.refreshToken());
        }
        String fragment = "access_token=" + URLEncoder.encode(pair.accessToken(), StandardCharsets.UTF_8);
        if (redirectUri == null) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "redirect_uri is required");
            return;
        }
        String target = redirectUri + (redirectUri.contains("?") ? "&" : "?") + fragment;
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, target);
    }

    @SuppressWarnings("unused")
    private static Duration ttlOf(GoogleOAuthProperties props) {
        return props.stateTtl();
    }
}
