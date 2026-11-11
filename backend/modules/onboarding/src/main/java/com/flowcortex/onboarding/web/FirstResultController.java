package com.flowcortex.onboarding.web;

import com.flowcortex.onboarding.application.FirstResultService;
import com.flowcortex.onboarding.infrastructure.FirstResultRepository.Reported;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/me/first-result} — the caller's own once-only first-result report; no route takes a user id. */
@RestController
@RequestMapping("/api/v1/me/first-result")
class FirstResultController {

    /** The run that was reported. */
    @Schema(name = "FirstResultClaimResponse")
    record FirstResultClaimResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID runId,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID workflowId) {
    }

    private final FirstResultService firstResults;
    private final UserDirectory userDirectory;

    FirstResultController(FirstResultService firstResults, UserDirectory userDirectory) {
        this.firstResults = firstResults;
        this.userDirectory = userDirectory;
    }

    @Operation(operationId = "claimFirstResult")
    @ApiResponse(responseCode = "200", description = "the account's first completed run, reported now")
    @ApiResponse(responseCode = "204", description = "nothing to report: no completed run, or already reported")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "the account is suspended",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "the token's user no longer exists",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/claim", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<FirstResultClaimResponse> claim(@AuthenticationPrincipal Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        UUID userId = userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
        Optional<Reported> claimed = firstResults.claim(userId);
        return claimed.map(r -> ResponseEntity.ok(new FirstResultClaimResponse(r.runId(), r.workflowId())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
