package com.flowcortex.onboarding.web;

import com.flowcortex.onboarding.application.OnboardingService;
import com.flowcortex.onboarding.application.OnboardingService.Builder;
import com.flowcortex.onboarding.application.OnboardingService.ProgressSaved;
import com.flowcortex.onboarding.application.OnboardingService.QuizSaved;
import com.flowcortex.onboarding.application.OnboardingService.Result;
import com.flowcortex.onboarding.application.OnboardingService.Status;
import com.flowcortex.onboarding.domain.StoredAnswers;
import com.flowcortex.onboarding.web.OnboardingChoices.ContentType;
import com.flowcortex.onboarding.web.OnboardingChoices.Industry;
import com.flowcortex.onboarding.web.OnboardingChoices.Purpose;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/v1/onboarding} — the caller's own onboarding record. The caller comes from the token
 * subject only; no route takes a user id. Bodies arrive as raw JSON because an absent field and an
 * explicit null mean different things, so the request schemas are documentation-only records.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
class OnboardingController {

    private final OnboardingService onboarding;
    private final UserDirectory userDirectory;

    OnboardingController(OnboardingService onboarding, UserDirectory userDirectory) {
        this.onboarding = onboarding;
        this.userDirectory = userDirectory;
    }

    @Operation(operationId = "getOnboardingStatus")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    OnboardingStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        Status status = onboarding.status(caller(jwt));
        StoredAnswers answers = status.record().answers();
        return new OnboardingStatusResponse(status.completed(), status.record().completedAt(),
                OnboardingChoices.of(answers.purposes(), Purpose.class), answers.source(), answers.aiLevel(),
                OnboardingChoices.of(answers.industries(), Industry.class),
                OnboardingChoices.of(answers.contentTypes(), ContentType.class),
                answers.usageType(), OnboardingProgressView.of(status.record().progress()), status.firstRunDone(),
                status.open(), status.entry());
    }

    @Operation(operationId = "saveOnboardingQuiz",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = OnboardingQuizBody.class))))
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "onboarding-invalid (errors names each bad field)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/quiz", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    QuizSaveResponse saveQuiz(@AuthenticationPrincipal Jwt jwt, @Parameter(hidden = true) @RequestBody JsonNode body) {
        return quizResponse(onboarding.submitQuiz(caller(jwt), body));
    }

    @Operation(operationId = "patchOnboardingQuiz",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = OnboardingQuizBody.class))))
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "onboarding-invalid (errors names each bad field)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PatchMapping(value = "/quiz", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    QuizSaveResponse patchQuiz(@AuthenticationPrincipal Jwt jwt, @Parameter(hidden = true) @RequestBody JsonNode body) {
        return quizResponse(onboarding.submitQuiz(caller(jwt), body));
    }

    @Operation(operationId = "saveOnboardingProgress",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = OnboardingProgressBody.class))))
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "the journey is closed or switched off; nothing was changed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "onboarding-invalid (errors names each bad field)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/progress", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ProgressResponse saveProgress(@AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestBody JsonNode body) {
        ProgressSaved saved = onboarding.saveProgress(caller(jwt), body);
        return new ProgressResponse(OnboardingProgressView.of(saved.progress()), saved.completedAt());
    }

    @Operation(operationId = "getOnboardingResult")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/result", produces = MediaType.APPLICATION_JSON_VALUE)
    OnboardingResultResponse result(@AuthenticationPrincipal Jwt jwt) {
        Result result = onboarding.result(caller(jwt));
        return new OnboardingResultResponse(result.completed(), result.recommendation().industry(),
                result.recommendation().track(), result.aiLevel(), result.recommendation().usageType());
    }

    @Operation(operationId = "resetOnboarding")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    OnboardingResetResponse reset(@AuthenticationPrincipal Jwt jwt) {
        onboarding.reset(caller(jwt));
        return new OnboardingResetResponse(false);
    }

    @Operation(operationId = "saveBuilderOnboardingProgress",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = BuilderProgressBody.class))))
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "onboarding-invalid (errors.state)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/builder/progress", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    BuilderOnboardingResponse saveBuilderProgress(@AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestBody JsonNode body) {
        return builderResponse(onboarding.saveBuilderProgress(caller(jwt), body));
    }

    @Operation(operationId = "completeBuilderOnboarding",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = BuilderCompleteBody.class))))
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", description = "unauthorized",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "onboarding-invalid (errors.reason)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/builder/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    BuilderOnboardingResponse completeBuilder(@AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestBody(required = false) JsonNode body) {
        return builderResponse(onboarding.completeBuilder(caller(jwt), body));
    }

    private UUID caller(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
    }

    private static QuizSaveResponse quizResponse(QuizSaved saved) {
        return new QuizSaveResponse(saved.completed(), saved.completedAt(), saved.skipped() ? Boolean.TRUE : null);
    }

    private static BuilderOnboardingResponse builderResponse(Builder builder) {
        return BuilderOnboardingResponse.of(builder.state(), builder.completedAt());
    }
}
