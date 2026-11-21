package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.application.WorkflowExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/workflow-examples} — the catalog of example workflows and "use this example", which creates the
 * caller's own draft. Authenticated by {@code :shared:security}'s default chain.
 */
@RestController
@RequestMapping("/api/v1/workflow-examples")
class WorkflowExamplesController {

    private final WorkflowExamples examples;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;

    WorkflowExamplesController(WorkflowExamples examples, CurrentUserResolver currentUserResolver,
            CreditAccounts creditAccounts) {
        this.examples = examples;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
    }

    @Operation(operationId = "listWorkflowExamples")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<WorkflowExampleResponse>> listWorkflowExamples(@AuthenticationPrincipal Jwt jwt,
            Locale locale) {
        currentUserResolver.require(jwt);
        return ResponseEntity.ok(examples.list(locale).stream()
                .map(example -> WorkflowExampleResponse.from(example, creditAccounts))
                .toList());
    }

    @Operation(operationId = "useWorkflowExample")
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/{exampleId}/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> useWorkflowExample(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String exampleId, Locale locale) {
        UUID ownerId = currentUserResolver.require(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowResponse.from(
                examples.instantiate(ownerId, exampleId, locale), creditAccounts));
    }
}
