package com.flowcortex.user.accountdeletion;

import com.flowcortex.user.accountdeletion.AccountDeletionService.Accepted;
import com.flowcortex.user.accountdeletion.AccountDeletionService.Status;
import com.flowcortex.user.accountdeletion.DeletionEligibility.Eligibility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * {@code /api/v1/me/account-deletion}: the signed-in member's own deletion request, status, preview
 * of blocking conditions and cancel. The caller is the token's subject; the routes are session-only.
 */
@RestController
@RequestMapping("/api/v1/me/account-deletion")
class AccountDeletionController {

    private static final int USER_AGENT_MAX = 512;
    private static final String CANCELLED = "CANCELLED";

    private final AccountDeletionService service;

    AccountDeletionController(AccountDeletionService service) {
        this.service = service;
    }

    @Operation(operationId = "getAccountDeletionStatus")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccountDeletionStatus> status(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(AccountDeletionStatus.from(service.status(userId(jwt))));
    }

    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "getAccountDeletionEligibility")
    @GetMapping(value = "/eligibility", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DeletionEligibilityView> eligibility(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(DeletionEligibilityView.from(service.eligibility(userId(jwt))));
    }

    @ApiResponse(responseCode = "202")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "requestAccountDeletion")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccountDeletionAccepted> request(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AccountDeletionRequestBody body, HttpServletRequest servletRequest) {
        Accepted accepted = service.request(userId(jwt), body.stepUpProof(), servletRequest.getRemoteAddr(),
                userAgent(servletRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AccountDeletionAccepted.from(accepted));
    }

    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "cancelAccountDeletion")
    @PostMapping(value = "/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccountDeletionCancelled> cancel(@AuthenticationPrincipal Jwt jwt,
            HttpServletRequest servletRequest) {
        Instant cancelledAt = service.cancel(userId(jwt), servletRequest.getRemoteAddr(), userAgent(servletRequest));
        return ResponseEntity.ok(new AccountDeletionCancelled(CANCELLED, cancelledAt));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        return agent == null || agent.length() <= USER_AGENT_MAX ? agent : agent.substring(0, USER_AGENT_MAX);
    }

    /** {@code stepUpProof}: a proof minted at {@code POST /api/v1/me/step-up} for purpose {@code ACCOUNT_DELETION}. */
    record AccountDeletionRequestBody(@NotBlank @Size(max = 200) String stepUpProof) {
    }

    record AccountDeletionStatus(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int gracePeriodDays,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccountDeletionState state,
            @Schema(description = "Null while the state is NONE") Instant requestedAt,
            @Schema(description = "When the grace period ends; null while the state is NONE") Instant scheduledAt) {

        static AccountDeletionStatus from(Status status) {
            return new AccountDeletionStatus(status.enabled(), status.gracePeriodDays(), status.state(),
                    status.requestedAt(), status.scheduledAt());
        }
    }

    record AccountDeletionAccepted(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccountDeletionState state,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant requestedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant scheduledAt) {

        static AccountDeletionAccepted from(Accepted accepted) {
            return new AccountDeletionAccepted(accepted.requestId(), accepted.state(), accepted.requestedAt(),
                    accepted.scheduledAt());
        }
    }

    record AccountDeletionCancelled(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = CANCELLED) String state,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant cancelledAt) {
    }

    record DeletionEligibilityView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean eligible,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DeletionReasonView> reasons) {

        static DeletionEligibilityView from(Eligibility eligibility) {
            return new DeletionEligibilityView(eligibility.eligible(), eligibility.reasons().stream()
                    .map(reason -> new DeletionReasonView(reason.key(), reason.message())).toList());
        }
    }

    record DeletionReasonView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String key,
            @Schema(description = "Already in the caller's language", requiredMode = Schema.RequiredMode.REQUIRED)
            String message) {
    }
}
