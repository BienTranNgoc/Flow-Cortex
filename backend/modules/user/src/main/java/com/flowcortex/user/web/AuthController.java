package com.flowcortex.user.web;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.TokenPairResponse;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.user.application.RegistrationRateLimiter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth}: password login, rate-limited self-registration,
 * Google OAuth callback (authorize lives in {@link OAuthController}), refresh,
 * logout, the caller's own profile ({@code me}), and self-service name update
 * ({@code profile}). Email/role/status are admin-mediated only.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";

    static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final UserDirectory userDirectory;
    private final RegistrationRateLimiter registrationRateLimiter;
    private final RefreshCookieSupport cookieSupport;

    AuthController(AuthService authService, UserDirectory userDirectory,
            RegistrationRateLimiter registrationRateLimiter, RefreshCookieSupport cookieSupport) {
        this.authService = authService;
        this.userDirectory = userDirectory;
        this.registrationRateLimiter = registrationRateLimiter;
        this.cookieSupport = cookieSupport;
    }

    @SecurityRequirements
    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TokenPairResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        TokenPairResponse pair = authService.login(request.email(), request.password(),
                Boolean.TRUE.equals(request.rememberMe()),
                servletRequest.getRemoteAddr(), servletRequest.getHeader(HttpHeaders.USER_AGENT));
        if (Boolean.TRUE.equals(request.rememberMe())) {
            cookieSupport.attachRefreshCookie(servletResponse, pair.refreshToken());
        }
        return ResponseEntity.ok(pair);
    }

    @SecurityRequirements
    @PostMapping(value = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TokenPairResponse> register(@Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        registrationRateLimiter.acquire(servletRequest.getRemoteAddr(), request.email());
        TokenPairResponse pair = authService.register(request.email(), request.password(), request.name(),
                Boolean.TRUE.equals(request.rememberMe()), servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        if (Boolean.TRUE.equals(request.rememberMe())) {
            cookieSupport.attachRefreshCookie(servletResponse, pair.refreshToken());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(pair);
    }

    @SecurityRequirements
    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TokenPairResponse> refresh(@RequestBody(required = false) RefreshRequest body,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String tokenFromBody = body == null ? null : body.refreshToken();
        String tokenFromCookie = readRefreshCookie(servletRequest);
        String raw = tokenFromBody != null ? tokenFromBody : tokenFromCookie;
        if (raw == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        TokenPairResponse pair = authService.refresh(raw, servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        if (tokenFromCookie != null) {
            cookieSupport.attachRefreshCookie(servletResponse, pair.refreshToken());
        }
        return ResponseEntity.ok(pair);
    }

    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) LogoutRequest body, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        UUID callerId = subjectId(jwt);
        Optional<String> raw = Optional.ofNullable(body).map(LogoutRequest::refreshToken)
                .or(() -> Optional.ofNullable(readRefreshCookie(servletRequest)));
        authService.logout(callerId, raw, servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        cookieSupport.clearRefreshCookie(servletResponse);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        UUID id = subjectId(jwt);
        CurrentUser user = userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        return ResponseEntity.ok(MeResponse.from(user));
    }

    @PutMapping(value = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MeResponse> updateProfile(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ProfileUpdateRequest request) {
        CurrentUser user = authService.updateOwnProfile(subjectId(jwt), request.name());
        return ResponseEntity.ok(MeResponse.from(user));
    }

    private static UUID subjectId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (REFRESH_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    record LoginRequest(@NotBlank String email, @NotBlank String password, Boolean rememberMe) {
    }

    record RegisterRequest(@NotBlank @jakarta.validation.constraints.Email String email,
            @NotBlank @jakarta.validation.constraints.Size(min = 8, max = 72) @MaxUtf8Bytes(72) String password,
            @NotBlank String name, Boolean rememberMe) {
    }

    record RefreshRequest(String refreshToken) {
    }

    record LogoutRequest(String refreshToken) {
    }

    record ProfileUpdateRequest(@NotBlank String name) {
    }

    record MeResponse(UUID id, String email, String name, String role, String status) {
        static MeResponse from(CurrentUser user) {
            return new MeResponse(user.id(), user.email(), user.name(), user.role(), user.status());
        }
    }
}
