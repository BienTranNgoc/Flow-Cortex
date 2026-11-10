package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.application.WorkflowTransferService;
import com.flowcortex.workflow.application.WorkflowTransferService.Exported;
import com.flowcortex.workflow.application.WorkflowTransferService.ImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;

/**
 * {@code /api/v1/admin/workflows} — moves a workflow between environments: export a live definition, review a
 * file (graded and held under a single-use token), then import the reviewed file as a draft of the admin.
 */
@RestController
@RequestMapping("/api/v1/admin/workflows")
@PreAuthorize("hasRole('ADMIN')")
class AdminWorkflowTransferController {

    private final WorkflowTransferService transferService;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;
    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    AdminWorkflowTransferController(WorkflowTransferService transferService,
            CurrentUserResolver currentUserResolver, CreditAccounts creditAccounts, MessageSource messages,
            LocaleResolver localeResolver) {
        this.transferService = transferService;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = WorkflowArtifactDocument.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "exportAdminWorkflow")
    @GetMapping(value = "/{id}/export", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> exportWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID adminId = currentUserResolver.requireAdmin(jwt);
        Exported exported = transferService.export(adminId, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(exported.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(exported.artifact().toString());
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "reviewAdminWorkflowImport")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = WorkflowArtifactDocument.class)))
    @PostMapping(value = "/import/review", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ImportReviewResponse> reviewImport(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        UUID adminId = currentUserResolver.requireAdmin(jwt);
        byte[] body = readCapped(request);
        Locale locale = localeResolver.resolveLocale(request);
        return ResponseEntity.ok(ImportReviewResponse.from(transferService.review(adminId, body),
                message -> messages.getMessage(message, locale)));
    }

    @ApiResponse(responseCode = "201", description = "Created", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WorkflowResponse.class)))
    @ApiResponse(responseCode = "200", description = "The token had already produced this workflow",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = WorkflowResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "importAdminWorkflow")
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> importWorkflow(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ImportWorkflowRequest request) {
        UUID adminId = currentUserResolver.requireAdmin(jwt);
        ImportResult result = transferService.importReviewed(adminId, request.token(),
                request.acknowledgeOutboundTargets());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(WorkflowResponse.from(result.view(), creditAccounts));
    }

    /** One byte past the cap is enough for the service to refuse, so an unbounded body is never held. */
    private static byte[] readCapped(HttpServletRequest request) {
        try {
            return request.getInputStream().readNBytes(WorkflowTransferService.MAX_ARTIFACT_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
