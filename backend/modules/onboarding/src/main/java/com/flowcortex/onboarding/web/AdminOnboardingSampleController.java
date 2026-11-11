package com.flowcortex.onboarding.web;

import com.flowcortex.onboarding.application.OnboardingSampleService;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/onboarding/sample} — the workflow new members are offered at the end of the
 * onboarding journey, the input typed for it and that input's translations. ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/admin/onboarding/sample")
@PreAuthorize("hasRole('ADMIN')")
class AdminOnboardingSampleController {

    private final OnboardingSampleService sample;
    private final UserDirectory userDirectory;

    AdminOnboardingSampleController(OnboardingSampleService sample, UserDirectory userDirectory) {
        this.sample = sample;
        this.userDirectory = userDirectory;
    }

    @Operation(operationId = "getAdminOnboardingSample")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "the caller is not an admin",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AdminOnboardingSample get() {
        return AdminOnboardingSample.of(sample.view());
    }

    @Operation(operationId = "updateAdminOnboardingSample")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "the caller is not an admin",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.workflowId, errors.sampleInput, errors.sourceLocale)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422",
            description = "ONBOARDING_SAMPLE_INELIGIBLE (errors.workflowId says why); the stored sample is unchanged",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    AdminOnboardingSample update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AdminOnboardingSampleUpdate body) {
        UUID workflowId = body.workflowId() == null ? null : UUID.fromString(body.workflowId());
        return AdminOnboardingSample.of(sample.set(admin(jwt), workflowId, body.sampleInput(), body.sourceLocale()));
    }

    @Operation(operationId = "updateAdminOnboardingSampleTranslation")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "the caller is not an admin",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.input, errors.sourceHash)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "no sample is set",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "the sample changed since sourceHash was read; nothing was stored",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "the sample's own language has no translation (errors.locale)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(value = "/translations/{locale}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    AdminOnboardingSample updateTranslation(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String locale,
            @Valid @RequestBody AdminOnboardingSampleTranslationUpdate body) {
        return AdminOnboardingSample.of(sample.saveTranslation(admin(jwt), locale, body.sourceHash(), body.input()));
    }

    private UUID admin(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
    }
}
