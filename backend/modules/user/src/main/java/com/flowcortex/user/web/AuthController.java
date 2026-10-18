package com.flowcortex.user.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AccessTokenResponse;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
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
 *
 * <p>Responses carry the access token only; the refresh token lives in an HttpOnly cookie that
 * refresh and logout read back (never a request body).
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthService authService;
    private final UserDirectory userDirectory;
    private final RefreshCookieSupport cookieSupport;

    AuthController(AuthService authService, UserDirectory userDirectory, RefreshCookieSupport cookieSupport) {
        this.authService = authService;
        this.userDirectory = userDirectory;
        this.cookieSupport = cookieSupport;
    }

    @SecurityRequirements
    @ApiResponse(responseCode = "200")
    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        AuthSession session = authService.login(request.email(), request.password(),
                Boolean.TRUE.equals(request.rememberMe()),
                servletRequest.getRemoteAddr(), servletRequest.getHeader(HttpHeaders.USER_AGENT));
        cookieSupport.attachRefreshCookie(servletRequest, servletResponse, session);
        return ResponseEntity.ok(session.toResponse());
    }

    @SecurityRequirements
    @ApiResponse(responseCode = "201")
    @PostMapping(value = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccessTokenResponse> register(@Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        AuthSession session = authService.register(request.email(), request.password(), request.name(),
                Boolean.TRUE.equals(request.rememberMe()), servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        cookieSupport.attachRefreshCookie(servletRequest, servletResponse, session);
        return ResponseEntity.status(HttpStatus.CREATED).body(session.toResponse());
    }

    @SecurityRequirements
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccessTokenResponse> refresh(HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Optional<AuthSession> session = Optional.ofNullable(cookieSupport.readRefreshCookie(servletRequest))
                .flatMap(raw -> authService.refresh(raw, servletRequest.getRemoteAddr(),
                        servletRequest.getHeader(HttpHeaders.USER_AGENT)));
        if (session.isEmpty()) {
            cookieSupport.clearRefreshCookie(servletRequest, servletResponse);
            throw new BadCredentialsException("Refresh token is not valid");
        }
        cookieSupport.attachRefreshCookie(servletRequest, servletResponse, session.get());
        return ResponseEntity.ok(session.get().toResponse());
    }

    @SecurityRequirements
    @ApiResponse(responseCode = "204")
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        authService.logout(Optional.ofNullable(cookieSupport.readRefreshCookie(servletRequest)),
                servletRequest.getRemoteAddr(), servletRequest.getHeader(HttpHeaders.USER_AGENT));
        cookieSupport.clearRefreshCookie(servletRequest, servletResponse);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        UUID id = subjectId(jwt);
        CurrentUser user = userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", id)));
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

    record LoginRequest(@NotBlank @jakarta.validation.constraints.Size(max = 254) String email,
            @NotBlank String password, Boolean rememberMe) {
    }

    record RegisterRequest(@NotBlank @jakarta.validation.constraints.Email
            @jakarta.validation.constraints.Size(max = 254) String email,
            @NotBlank @jakarta.validation.constraints.Size(min = 8, max = 72) @MaxUtf8Bytes(72) String password,
            @NotBlank String name, Boolean rememberMe) {
    }

    record ProfileUpdateRequest(@NotBlank String name) {
    }

    record MeResponse(UUID id, String email, String name, String role, String status) {
        static MeResponse from(CurrentUser user) {
            return new MeResponse(user.id(), user.email(), user.name(), user.role(), user.status());
        }
    }
}
