package com.flowcortex.user.phone;

import com.flowcortex.user.phone.PhoneVerificationService.PhoneStatus;
import com.flowcortex.user.phone.PhoneVerificationService.SentCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
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

    private final PhoneVerificationService service;

    PhoneController(PhoneVerificationService service) {
        this.service = service;
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

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    record SendCodeRequest(
            @NotNull(message = INVALID_NUMBER)
            @Pattern(regexp = PhoneNumbers.VN_MOBILE, message = INVALID_NUMBER)
            @Schema(example = "0912345678")
            String phone) {
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
            String maskedPhone) {

        static PhoneResponse from(PhoneStatus status) {
            return new PhoneResponse(status.phone(), status.verified(), status.maskedPhone());
        }
    }

    record SendCodeResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String maskedPhone,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Seconds before a resend is accepted")
            long resendAfterSeconds,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {
    }
}
