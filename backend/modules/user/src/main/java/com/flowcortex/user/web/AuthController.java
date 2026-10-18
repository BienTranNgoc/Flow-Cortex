package com.flowcortex.user.web;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AccessTokenResponse;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.api.ConfirmedEmailSession;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * {@code /api/v1/auth}: password login, rate-limited self-registration with its email
 * confirmation ({@code confirm-email}, {@code resend-confirmation}), Google OAuth callback
 * (authorize lives in {@link OAuthController}), refresh, logout, the caller's own profile
 * ({@code me}), and self-service name update
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

    /** 202 without a session while email confirmation is on; 201 with the first session when it is off. */
    @SecurityRequirements
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = AccessTokenResponse.class)))
    @ApiResponse(responseCode = "202", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ConfirmationSentResponse.class)))
    @PostMapping(value = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        Optional<AuthSession> session = authService.register(request.email(), request.password(),
                request.firstName(), request.lastName(), request.username(),
                Boolean.TRUE.equals(request.rememberMe()), servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        if (session.isEmpty()) {
            return ResponseEntity.accepted().body(ConfirmationSentResponse.SENT);
        }
        cookieSupport.attachRefreshCookie(servletRequest, servletResponse, session.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(session.get().toResponse());
    }

    @SecurityRequirements
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/confirm-email", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ConfirmEmailResponse> confirmEmail(@Valid @RequestBody ConfirmEmailRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        ConfirmedEmailSession confirmed = authService.confirmEmail(request.token(), servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        return signedInFromLink(confirmed, servletRequest, servletResponse);
    }

    /** Opens an emailed set-password link: signs its account in and hands out the password-setup token. */
    @SecurityRequirements
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/password-link", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ConfirmEmailResponse> openPasswordLink(@Valid @RequestBody ConfirmEmailRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        ConfirmedEmailSession opened = authService.openPasswordLink(request.token(), servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        return signedInFromLink(opened, servletRequest, servletResponse);
    }

    private ResponseEntity<ConfirmEmailResponse> signedInFromLink(ConfirmedEmailSession opened,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        AuthSession session = opened.session();
        cookieSupport.attachRefreshCookie(servletRequest, servletResponse, session);
        return ResponseEntity.ok(new ConfirmEmailResponse(session.accessToken(), session.expiresIn(),
                opened.passwordSetupRequired(), opened.passwordSetupToken()));
    }

    /**
     * Always 202, whether or not the email belongs to an account that needs a confirmation or
     * set-password link.
     */
    @SecurityRequirements
    @ApiResponse(responseCode = "202")
    @PostMapping(value = "/resend-confirmation", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ConfirmationSentResponse> resendConfirmation(@Valid @RequestBody ResendConfirmationRequest request,
            HttpServletRequest servletRequest) {
        authService.resendConfirmation(request.email(), servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.accepted().body(ConfirmationSentResponse.SENT);
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

    record LoginRequest(@NotBlank @jakarta.validation.constraints.Size(max = 254) String email,
            @NotBlank String password, Boolean rememberMe) {
    }

    /** {@code @NotBlank} refuses whitespace-only names; the service stores them trimmed. */
    record RegisterRequest(
            @NotBlank(message = "First name is required") @Size(max = 100) String firstName,
            @NotBlank(message = "Last name is required") @Size(max = 100) String lastName,
            @Pattern(regexp = "^[a-z0-9_]{3,20}$", message = "Use 3–20 lowercase letters, digits or _") String username,
            @NotBlank @jakarta.validation.constraints.Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) @MaxUtf8Bytes(72) String password,
            Boolean rememberMe) {
    }

    record ConfirmEmailRequest(@NotBlank @Size(max = 128) String token) {
    }

    /**
     * {@code passwordSetupRequired}: the account has no password yet; the app asks for one next and
     * sends {@code passwordSetupToken} with it ({@code null} when no password is needed).
     */
    record ConfirmEmailResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessToken,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long expiresIn,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean passwordSetupRequired,
            String passwordSetupToken) {
    }

    record ResendConfirmationRequest(@NotBlank @jakarta.validation.constraints.Email @Size(max = 254) String email) {
    }

    record ConfirmationSentResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status) {
        static final ConfirmationSentResponse SENT = new ConfirmationSentResponse("confirmation_sent");
    }

    record ProfileUpdateRequest(@NotBlank String name) {
    }

    /**
     * {@code passwordSetupRequired}: the account has no password and no Google sign-in, so once this
     * session ends nothing gets it back in; the app sends the member to set a password first.
     */
    record MeResponse(UUID id, String email, String name, String role, String status, boolean hasPassword,
            boolean passwordSetupRequired) {
        static MeResponse from(CurrentUser user) {
            return new MeResponse(user.id(), user.email(), user.name(), user.role(), user.status(),
                    user.hasPassword(), user.passwordSetupRequired());
        }
    }
}
