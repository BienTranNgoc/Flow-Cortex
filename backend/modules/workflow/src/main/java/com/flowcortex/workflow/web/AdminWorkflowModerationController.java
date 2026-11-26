package com.flowcortex.workflow.web;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequireAnyPermission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.audit.AdminAudit;
import com.flowcortex.workflow.application.ModerationRules.Action;
import com.flowcortex.workflow.application.WorkflowModerationService;
import com.flowcortex.workflow.web.ModerationRequests.ModerationRequest;
import com.flowcortex.workflow.web.ModerationResponses.ModerationHistoryEntryResponse;
import com.flowcortex.workflow.web.ModerationResponses.ModerationOutcomeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/moderation/workflows} — staff hide, remove or restore a workflow without a report, and read
 * its moderation history. The three writes need {@code moderation.content.hide}; the history needs it or
 * {@code moderation.report.view}. Each write is audited by action and workflow id, never by its reason or note.
 */
@RestController
@RequestMapping("/api/v1/admin/moderation/workflows")
@ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        schema = @Schema(implementation = ProblemDetail.class)))
class AdminWorkflowModerationController {

    private final WorkflowModerationService moderation;
    private final CurrentUserResolver currentUserResolver;

    AdminWorkflowModerationController(WorkflowModerationService moderation,
            CurrentUserResolver currentUserResolver) {
        this.moderation = moderation;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(operationId = "hideWorkflow")
    @ApiResponse(responseCode = "200")
    @AdminAudit("workflow.hide")
    @RequirePermission(Permission.MODERATION_CONTENT_HIDE)
    @PostMapping(value = "/{id}/hide", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ModerationOutcomeResponse> hide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) ModerationRequest request) {
        return act(jwt, id, Action.HIDE, request);
    }

    @Operation(operationId = "removeWorkflow")
    @ApiResponse(responseCode = "200")
    @AdminAudit("workflow.remove")
    @RequirePermission(Permission.MODERATION_CONTENT_HIDE)
    @PostMapping(value = "/{id}/remove", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ModerationOutcomeResponse> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) ModerationRequest request) {
        return act(jwt, id, Action.REMOVE, request);
    }

    @Operation(operationId = "restoreWorkflow")
    @ApiResponse(responseCode = "200")
    @AdminAudit("workflow.restore")
    @RequirePermission(Permission.MODERATION_CONTENT_HIDE)
    @PostMapping(value = "/{id}/restore", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ModerationOutcomeResponse> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) ModerationRequest request) {
        return act(jwt, id, Action.RESTORE, request);
    }

    @Operation(operationId = "getWorkflowModerationHistory")
    @ApiResponse(responseCode = "200")
    @RequireAnyPermission({Permission.MODERATION_REPORT_VIEW, Permission.MODERATION_CONTENT_HIDE})
    @GetMapping(value = "/{id}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<ModerationHistoryEntryResponse>> history(@PathVariable UUID id) {
        return ResponseEntity.ok(moderation.history(id).stream().map(ModerationHistoryEntryResponse::from).toList());
    }

    private ResponseEntity<ModerationOutcomeResponse> act(Jwt jwt, UUID workflowId, Action action,
            ModerationRequest request) {
        UUID staffId = currentUserResolver.require(jwt);
        ModerationRequest body = ModerationRequest.orEmpty(request);
        return ResponseEntity.ok(ModerationOutcomeResponse.from(
                moderation.actDirect(staffId, workflowId, action, body.reason(), body.internalNote())));
    }
}
