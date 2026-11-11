package com.flowcortex.user.phone;

import com.flowcortex.user.phone.PhoneVerificationService.PhoneStatus;
import com.flowcortex.user.phone.PhoneVerificationService.SentCode;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
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

/** {@code /api/v1/me/phone}: the signed-in member's own phone number, behind {@link PhoneVerificationSwitch}. */
@RestController
@RequestMapping("/api/v1/me/phone")
class PhoneController {

    private static final String INVALID_NUMBER = "Enter a valid Vietnamese mobile number";
    private static final String INVALID_CODE = "Enter the 6-digit code";
    private static final int USER_AGENT_MAX = 512;

    private final PhoneVerificationService service;
    private final PhoneChangeService changes;

    PhoneController(PhoneVerificationService service, PhoneChangeService changes) {
        this.service = service;
        this.changes = changes;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PhoneResponse> current(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(PhoneResponse.from(service.current(userId(jwt))));
    }

    @PostMapping(value = "/send-code", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SendCodeResponse> sendCode(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SendCodeRequest request, HttpServletRequest servletRequest) {
        SentCode sent = service.sendCode(userId(jwt), request.phone(), servletRequest.getRemoteAddr());
        return ResponseEntity.ok(new SendCodeResponse(sent.maskedPhone(), sent.resendAfterSeconds(), sent.expiresAt()));
    }

    @PostMapping(value = "/verify", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PhoneResponse> verify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyCodeRequest request) {
        UUID userId = userId(jwt);
        service.verify(userId, request.code());
        return ResponseEntity.ok(PhoneResponse.from(service.current(userId)));
    }

    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/change/start", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SendCodeResponse> changeStart(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangeStartRequest request, HttpServletRequest servletRequest) {
        SentCode sent = changes.start(userId(jwt), request.phone(), request.stepUpProof(),
                servletRequest.getRemoteAddr());
        return ResponseEntity.ok(new SendCodeResponse(sent.maskedPhone(), sent.resendAfterSeconds(), sent.expiresAt()));
    }

    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/change/verify", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PhoneResponse> changeVerify(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody VerifyCodeRequest request, HttpServletRequest servletRequest) {
        UUID userId = userId(jwt);
        changes.verify(userId, request.code(), servletRequest.getRemoteAddr(), userAgent(servletRequest));
        return ResponseEntity.ok(PhoneResponse.from(service.current(userId)));
    }

    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/change/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PhoneResponse> changeCancel(@AuthenticationPrincipal Jwt jwt, HttpServletRequest servletRequest) {
        UUID userId = userId(jwt);
        changes.cancel(userId, servletRequest.getRemoteAddr(), userAgent(servletRequest));
        return ResponseEntity.ok(PhoneResponse.from(service.current(userId)));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        return agent == null || agent.length() <= USER_AGENT_MAX ? agent : agent.substring(0, USER_AGENT_MAX);
    }

    record SendCodeRequest(
            @NotNull(message = INVALID_NUMBER)
            @Pattern(regexp = PhoneNumbers.VN_MOBILE, message = INVALID_NUMBER)
            @Schema(example = "0912345678")
            String phone) {
    }

    /** {@code stepUpProof}: a proof minted at {@code POST /api/v1/me/step-up} for purpose {@code PHONE_CHANGE}. */
    record ChangeStartRequest(
            @NotNull(message = INVALID_NUMBER)
            @Pattern(regexp = PhoneNumbers.VN_MOBILE, message = INVALID_NUMBER)
            @Schema(example = "0987654321")
            String phone,
            @NotBlank @Size(max = 128) String stepUpProof) {
    }

    record VerifyCodeRequest(
            @NotNull(message = INVALID_CODE)
            @Pattern(regexp = "^\\d{6}$", message = INVALID_CODE)
            String code) {
    }

    record PhoneResponse(
            @Schema(description = "Verified number in E.164, or null") String phone,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean verified,
            @Schema(description = "The verified number as shown to the member (carrier digit and last three digits)")
            String maskedPhone,
            @Schema(description = "While a number change can still be undone, when that stops; otherwise null")
            Instant cancelAvailableUntil) {

        static PhoneResponse from(PhoneStatus status) {
            return new PhoneResponse(status.phone(), status.verified(), status.maskedPhone(),
                    status.cancelAvailableUntil());
        }
    }

    record SendCodeResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String maskedPhone,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Seconds before a resend is accepted")
            long resendAfterSeconds,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {
    }
}
