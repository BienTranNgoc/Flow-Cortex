package com.flowcortex.user.stepup;

import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.stepup.StepUpService.MintedProof;
import com.flowcortex.user.web.MaxUtf8Bytes;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/me/step-up}: the signed-in member re-enters the account password and gets a
 * short-lived, single-use proof for one purpose. A wrong password answers 422, never 401.
 */
@RestController
@RequestMapping("/api/v1/me/step-up")
class StepUpController {

    private static final int USER_AGENT_MAX = 512;

    private final StepUpService service;

    StepUpController(StepUpService service) {
        this.service = service;
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
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StepUpResponse> stepUp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StepUpRequest request,
            HttpServletRequest servletRequest) {
        MintedProof minted = service.mint(UUID.fromString(jwt.getSubject()), request.password(),
                StepUpPurpose.valueOf(request.purpose()),
                servletRequest.getRemoteAddr(), userAgent(servletRequest));
        return ResponseEntity.ok(new StepUpResponse(minted.proof(), minted.expiresAt()));
    }

    private static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        return agent == null || agent.length() <= USER_AGENT_MAX ? agent : agent.substring(0, USER_AGENT_MAX);
    }

    /** {@code password}: the account's current password, up to BCrypt's 72 bytes. */
    record StepUpRequest(
            @NotBlank @Size(max = 72) @MaxUtf8Bytes(72) String password,
            @NotNull @KnownStepUpPurpose @Schema(implementation = StepUpPurpose.class) String purpose) {
    }

    record StepUpResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String stepUpProof,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {
    }
}
