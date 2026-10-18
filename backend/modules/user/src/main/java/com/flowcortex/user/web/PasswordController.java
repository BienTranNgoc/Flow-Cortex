package com.flowcortex.user.web;

import com.flowcortex.user.api.AccessTokenResponse;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.api.PasswordSetup;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me/password}: the signed-in member's first password, for an account that has none,
 * with the names and username chosen alongside it. A bearer token alone is not enough: the request
 * carries the one-time setup token an emailed link handed to this browser. Every other session ends;
 * the response replaces the caller's session with a new one. 409 once the account has a password.
 */
@RestController
@RequestMapping("/api/v1/me/password")
class PasswordController {

    private final AuthService authService;
    private final RefreshCookieSupport cookieSupport;

    PasswordController(AuthService authService, RefreshCookieSupport cookieSupport) {
        this.authService = authService;
        this.cookieSupport = cookieSupport;
    }

    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccessTokenResponse> setPassword(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SetPasswordRequest request, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        AuthSession session = authService.setPassword(UUID.fromString(jwt.getSubject()),
                new PasswordSetup(request.passwordSetupToken(), request.password(), request.firstName(),
                        request.lastName(), request.username()),
                servletRequest.getRemoteAddr(), servletRequest.getHeader(HttpHeaders.USER_AGENT));
        cookieSupport.attachRefreshCookie(servletRequest, servletResponse, session);
        return ResponseEntity.ok(session.toResponse());
    }

    /** The registration rules for the password, names and username. */
    record SetPasswordRequest(
            @NotBlank @Size(max = 128) String passwordSetupToken,
            @NotBlank @Size(min = 8, max = 72) @MaxUtf8Bytes(72) String password,
            @NotBlank(message = "First name is required") @Size(max = 100) String firstName,
            @NotBlank(message = "Last name is required") @Size(max = 100) String lastName,
            @Pattern(regexp = "^[a-z0-9_]{3,20}$", message = "Use 3–20 lowercase letters, digits or _") String username) {
    }
}
