package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.VersionSavedView;
import com.flowcortex.workflow.api.WorkflowVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/workflows/{id}} — version history, comparison, restore, the published pointer and the caller's
 * own pin. Writes are owner-only; any member who can see the workflow may read its released versions as skeletons
 * and pin one for their own runs. Authenticated by {@code :shared:security}'s default chain.
 */
@RestController
@RequestMapping("/api/v1/workflows/{id}")
class WorkflowVersionsController {

    private static final int MAX_PAGE = 100;

    private final WorkflowVersionService versions;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;

    WorkflowVersionsController(WorkflowVersionService versions, CurrentUserResolver currentUserResolver,
            CreditAccounts creditAccounts) {
        this.versions = versions;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = WorkflowVersionPageResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "listWorkflowVersions")
    @GetMapping(value = "/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowVersionPageResponse> listWorkflowVersions(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @RequestParam(required = false) @Min(1) Integer beforeVersion,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE) int limit) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowVersionPageResponse.from(versions.list(callerId, id, beforeVersion, limit)));
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = WorkflowVersionDetailResponse.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "getWorkflowVersion")
    @GetMapping(value = "/versions/{version}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowVersionDetailResponse> getWorkflowVersion(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @PathVariable int version) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowVersionDetailResponse.from(versions.get(callerId, id, version)));
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VersionSavedResponse.class)))
    @ApiResponse(responseCode = "201", description = "Created", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VersionSavedResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "saveWorkflowVersion")
    @PostMapping(value = "/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VersionSavedResponse> saveWorkflowVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody(required = false) SaveVersionRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return saved(versions.save(callerId, id, request == null ? null : request.changelog()));
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = WorkflowVersionResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "renameWorkflowVersion")
    @PatchMapping(value = "/versions/{version}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowVersionResponse> renameWorkflowVersion(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @PathVariable int version, @Valid @RequestBody RenameVersionRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowVersionResponse.from(
                versions.rename(callerId, id, version, request.changelog())));
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VersionSavedResponse.class)))
    @ApiResponse(responseCode = "201", description = "Created", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VersionSavedResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "rollbackWorkflowVersion")
    @PostMapping(value = "/versions/{version}/rollback", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VersionSavedResponse> rollbackWorkflowVersion(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @PathVariable int version,
            @Valid @RequestBody(required = false) SaveVersionRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return saved(versions.rollback(callerId, id, version, request == null ? null : request.changelog()));
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VersionSavedResponse.class)))
    @ApiResponse(responseCode = "201", description = "Created", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VersionSavedResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "hotfixWorkflow")
    @PostMapping(value = "/hotfix", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VersionSavedResponse> hotfixWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody(required = false) HotfixRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return saved(versions.hotfix(callerId, id, request == null ? null : request.note()));
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = WorkflowVersionDiffResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "diffWorkflowVersions")
    @GetMapping(value = "/diff", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowVersionDiffResponse> diffWorkflowVersions(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @RequestParam(required = false) String source,
            @RequestParam(required = false) String target) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowVersionDiffResponse.from(versions.diff(callerId, id, source, target)));
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WorkflowResponse.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "setPublishedWorkflowVersion")
    @PatchMapping(value = "/published-version", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> setPublishedWorkflowVersion(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @RequestBody(required = false) PublishedVersionRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowResponse.from(
                versions.setPublishedVersion(callerId, id, request == null ? null : request.versionId()),
                creditAccounts));
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VersionPinResponse.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "getWorkflowVersionPin")
    @GetMapping(value = "/pin", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VersionPinResponse> getWorkflowVersionPin(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(VersionPinResponse.from(versions.getPin(callerId, id)));
    }

    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VersionPinResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "pinWorkflowVersion")
    @PostMapping(value = "/pin", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VersionPinResponse> pinWorkflowVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody PinVersionRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(VersionPinResponse.from(
                versions.pin(callerId, id, request.version(), request.reason())));
    }

    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "unpinWorkflowVersion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/pin")
    ResponseEntity<Void> unpinWorkflowVersion(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        versions.unpin(currentUserResolver.require(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<VersionSavedResponse> saved(VersionSavedView view) {
        HttpStatus status = view.deduplicated() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(VersionSavedResponse.from(view));
    }
}
