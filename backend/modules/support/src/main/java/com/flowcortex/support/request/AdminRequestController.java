package com.flowcortex.support.request;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.support.request.RequestViews.AdminDetail;
import com.flowcortex.support.request.RequestViews.AdminPage;
import com.flowcortex.support.request.RequestViews.Count;
import com.flowcortex.support.web.SupportCaller;
import com.flowcortex.user.api.AdminPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/support/requests} — the admin inbox: newest activity first, 20 per page; replies choose the
 * resulting status, and status changes follow the lifecycle table only. Not affected by the Help Center switch.
 */
@RestController
@RequestMapping("/api/v1/admin/support/requests")
class AdminRequestController {

    private final RequestService requests;
    private final SupportCaller caller;
    private final AdminPermissions adminPermissions;

    AdminRequestController(RequestService requests, SupportCaller caller, AdminPermissions adminPermissions) {
        this.requests = requests;
        this.caller = caller;
        this.adminPermissions = adminPermissions;
    }

    /** {@code status} is the request's status after the reply; {@code resolved} also needs the close key. */
    @Schema(name = "AdminSupportRequestReply")
    record AdminReplyRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED) String body,
            @ArraySchema(maxItems = AttachmentService.MAX_PER_MESSAGE) List<UUID> uploadIds,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"waiting_member", "resolved"})
            String status) {
    }

    @Schema(name = "AdminSupportRequestStatusChange")
    record StatusRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"in_progress", "resolved", "closed"})
            String status) {
    }

    @Operation(operationId = "listAdminSupportRequests")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.status, errors.type, errors.page)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.SUPPORT_TICKET_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    AdminPage list(
            @Parameter(schema = @Schema(allowableValues = {"new", "in_progress", "waiting_member", "resolved",
                    "closed"}))
            @RequestParam(required = false) String status,
            @Parameter(schema = @Schema(allowableValues = {"account", "credit", "workflow", "technical", "other"}))
            @RequestParam(required = false) String type,
            @Parameter(schema = @Schema(type = "integer", format = "int32", defaultValue = "0", minimum = "0",
                    maximum = "" + RequestService.MAX_PAGE))
            @RequestParam(defaultValue = "0") int page) {
        return requests.page(status, type, page);
    }

    @Operation(operationId = "countOpenSupportRequests")
    @RequirePermission(Permission.SUPPORT_TICKET_VIEW)
    @GetMapping(value = "/open-count", produces = MediaType.APPLICATION_JSON_VALUE)
    Count openCount() {
        return new Count(requests.openCount());
    }

    @Operation(operationId = "getAdminSupportRequest")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "request-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.SUPPORT_TICKET_VIEW)
    @GetMapping(value = "/{number}", produces = MediaType.APPLICATION_JSON_VALUE)
    AdminDetail detail(@PathVariable long number) {
        return requests.adminDetail(number);
    }

    // @ResponseStatus documents 201 for springdoc/orval only; the ResponseEntity sets the same status.
    @Operation(operationId = "replyToAdminSupportRequest")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors), attachment-rejected",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "request-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409",
            description = "request-closed, request-state-changed, requester-deleted, idempotency-key-reused",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "attachments-unavailable (uploadIds sent)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.SUPPORT_TICKET_REPLY)
    @PostMapping(value = "/{number}/messages", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AdminDetail> reply(@AuthenticationPrincipal Jwt jwt, @PathVariable long number,
            @Parameter(schema = @Schema(format = "uuid"))
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AdminReplyRequest body) {
        UUID adminId = caller.require(jwt).id();
        if ("resolved".equals(body.status())) {
            adminPermissions.require(adminId, Permission.SUPPORT_TICKET_CLOSE);
        }
        AdminDetail replied = requests.adminReply(adminId, number, body.body(), body.uploadIds(), body.status(),
                IdempotencyGuard.key(idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(replied);
    }

    @Operation(operationId = "setAdminSupportRequestStatus")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.status)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "request-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "transition-not-allowed, request-state-changed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.SUPPORT_TICKET_CLOSE)
    @PutMapping(value = "/{number}/status", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    AdminDetail status(@PathVariable long number, @RequestBody StatusRequest body) {
        return requests.setStatus(number, body.status());
    }
}
