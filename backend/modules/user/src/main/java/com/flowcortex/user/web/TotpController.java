package com.flowcortex.user.web;

import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.application.JwtClaims;
import com.flowcortex.user.totp.TotpProblems.ChallengeInvalid;
import com.flowcortex.user.totp.TotpService;
import io.swagger.v3.oas.annotations.Operation;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth/totp*} and {@code /api/v1/auth/login/totp}: the signed-in account manages its own second
 * factor, and a sign-in that owes a code completes here. They sit under {@code /api/v1/auth} so the refresh
 * cookie reaches them. Responses that carry a secret or recovery codes are {@code no-store}.
 */
@RestController
@RequestMapping("/api/v1/auth")
class TotpController {

    private static final String CODE_SHAPE = "^[0-9 ]{6,7}$|^[A-Za-z2-7 -]{10,13}$";

    private final TotpService totp;
    private final AuthService authService;
    private final RefreshCookieSupport cookieSupport;

    TotpController(TotpService totp, AuthService authService, RefreshCookieSupport cookieSupport) {
        this.totp = totp;
        this.authService = authService;
        this.cookieSupport = cookieSupport;
    }

    @Operation(operationId = "getTotpStatus")
    @GetMapping(value = "/totp", produces = MediaType.APPLICATION_JSON_VALUE)
    TotpStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return TotpStatusResponse.of(totp.status(subjectId(jwt)), JwtClaims.mfaOf(jwt));
    }

    @Operation(operationId = "startTotpSetup")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = TotpSetupResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/totp/setup", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TotpSetupResponse> setup(@AuthenticationPrincipal Jwt jwt) {
        TotpService.Setup setup = totp.setup(subjectId(jwt));
        return noStore(new TotpSetupResponse(setup.secret(), setup.otpauthUri(), setup.qrDataUrl()));
    }

    /**
     * Turns TOTP on for the pending secret, replaces every session of the account with one that passed
     * TOTP (refresh cookie set) and returns the recovery codes once.
     */
    @Operation(operationId = "enableTotp")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = TotpEnableResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "423", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/totp/enable", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TotpEnableResponse> enable(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TotpCodeRequest request, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        TotpService.EnableResult result = totp.enable(subjectId(jwt), request.code(),
                servletRequest.getRemoteAddr(), servletRequest.getHeader(HttpHeaders.USER_AGENT),
                cookieSupport.readRefreshCookie(servletRequest));
        AuthSession session = result.session();
        cookieSupport.attachRefreshCookie(servletRequest, servletResponse, session);
        return noStore(new TotpEnableResponse(result.recoveryCodes(), session.accessToken(), session.expiresIn()));
    }

    @Operation(operationId = "disableTotp")
    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "423", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/totp/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TotpCodeRequest request,
            HttpServletRequest servletRequest) {
        totp.disable(subjectId(jwt), request.code(), servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.noContent().build();
    }

    /** Replaces every recovery code with ten new ones, shown once; only a TOTP code authorizes it. */
    @Operation(operationId = "regenerateTotpRecoveryCodes")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = TotpRecoveryCodesResponse.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "423", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/totp/recovery-codes", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TotpRecoveryCodesResponse> regenerate(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TotpCodeRequest request, HttpServletRequest servletRequest) {
        List<String> codes = totp.regenerate(subjectId(jwt), request.code(), servletRequest.getRemoteAddr(),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        return noStore(new TotpRecoveryCodesResponse(codes));
    }

    /**
     * Completes a sign-in that owes a code: reads the challenge cookie (no bearer token is sent), and on a valid
     * code opens the session, sets the refresh cookie and clears the challenge cookie. The cookie stays while the
     * code is merely wrong, so the member can retry until the challenge dies.
     */
    @SecurityRequirements
    @Operation(operationId = "completeTotpSignIn")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = TotpSignInResponse.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "423", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/login/totp", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TotpSignInResponse> completeSignIn(@Valid @RequestBody TotpCodeRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String challenge = cookieSupport.readChallengeCookie(servletRequest);
        if (challenge == null || challenge.isBlank()) {
            throw new ChallengeInvalid();
        }
        AuthSession session;
        try {
            session = authService.completeTotpSignIn(challenge, request.code(), servletRequest.getRemoteAddr(),
                    servletRequest.getHeader(HttpHeaders.USER_AGENT));
        } catch (ChallengeInvalid ex) {
            cookieSupport.clearChallengeCookie(servletRequest, servletResponse);
            throw ex;
        }
        cookieSupport.clearChallengeCookie(servletRequest, servletResponse);
        cookieSupport.attachRefreshCookie(servletRequest, servletResponse, session);
        return ResponseEntity.ok(new TotpSignInResponse(session.accessToken(), session.expiresIn(),
                session.persistent()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static UUID subjectId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** A six-digit code (spaces allowed) or a recovery code (ten base32 characters; spaces and hyphens allowed). */
    record TotpCodeRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = CODE_SHAPE)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code) {
    }

    /**
     * {@code required}: a console account while the requirement is on; {@code verified}: this session passed a
     * code; {@code lockedUntil}: null unless the second factor is locked.
     */
    record TotpStatusResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean pending,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean required,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean verified,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int recoveryCodesLeft,
            Instant lockedUntil) {
        static TotpStatusResponse of(TotpService.Status status, boolean verified) {
            return new TotpStatusResponse(status.enabled(), status.pending(), status.required(), verified,
                    status.recoveryCodesLeft(), status.lockedUntil());
        }
    }

    /** The only response that ever carries the secret. */
    record TotpSetupResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String secret,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String otpauthUri,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String qrDataUrl) {
    }

    record TotpEnableResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> recoveryCodes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessToken,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long expiresIn) {
    }

    record TotpRecoveryCodesResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> recoveryCodes) {
    }

    /** {@code persistent}: the sign-in's remember-me, so the app stores the token like a password login does. */
    record TotpSignInResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessToken,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long expiresIn,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean persistent) {
    }
}
