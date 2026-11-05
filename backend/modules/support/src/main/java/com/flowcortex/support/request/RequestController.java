package com.flowcortex.support.request;

import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.support.request.RequestService.NewRequest;
import com.flowcortex.support.request.RequestViews.Count;
import com.flowcortex.support.request.RequestViews.Detail;
import com.flowcortex.support.request.RequestViews.Summary;
import com.flowcortex.support.request.RequestViews.UploadGrantView;
import com.flowcortex.support.web.SupportCaller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/support/requests} and {@code /api/v1/support/uploads} — a member's own requests. Another member's
 * number answers exactly like one that does not exist. A suspended member is refused on every route (403).
 */
@RestController
class RequestController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RequestService requests;
    private final AttachmentService attachments;
    private final SupportCaller caller;

    RequestController(RequestService requests, AttachmentService attachments, SupportCaller caller) {
        this.requests = requests;
        this.attachments = attachments;
        this.caller = caller;
    }

    /** {@code fileName} only picks the type; it is never stored. */
    @Schema(name = "SupportUploadRequest")
    record UploadRequest(@NotBlank @Size(max = 255) String fileName, @NotNull Long size) {
    }

    /** Lengths are checked after trimming, in the service. */
    @Schema(name = "SupportRequestCreate")
    record CreateRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"account", "credit", "workflow", "technical", "other"}) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED) String subject,
            @Schema(requiredMode = RequiredMode.REQUIRED) String description,
            @ArraySchema(maxItems = AttachmentService.MAX_PER_MESSAGE) List<UUID> uploadIds,
            UUID aiConversationId) {
    }

    @Schema(name = "SupportRequestReply")
    record ReplyRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED) String body,
            @ArraySchema(maxItems = AttachmentService.MAX_PER_MESSAGE) List<UUID> uploadIds) {
    }

    // @ResponseStatus documents 201/204 for springdoc/orval only; the ResponseEntity sets the same status.
    @Operation(operationId = "requestSupportUpload")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors), attachment-rejected",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "support-disabled, attachments-unavailable",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/api/v1/support/uploads", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UploadGrantView> upload(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UploadRequest body) {
        UUID memberId = caller.require(jwt).id();
        UploadGrant grant = attachments.grant(memberId, body.fileName(), body.size());
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadGrantView(grant.uploadId(), grant.url(),
                grant.method(), grant.headers(), grant.expiresAt(), AttachmentService.MAX_BYTES));
    }

    @Operation(operationId = "createSupportRequest")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors), attachment-rejected",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "idempotency-key-reused",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "support-disabled, attachments-unavailable (uploadIds sent)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/api/v1/support/requests", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Detail> create(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(format = "uuid"))
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody CreateRequest body) {
        UUID memberId = caller.require(jwt).id();
        Detail created = requests.create(memberId, new NewRequest(body.type(), body.subject(), body.description(),
                body.uploadIds(), body.aiConversationId()), IdempotencyGuard.key(idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(operationId = "listSupportRequests")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "503", description = "support-disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/api/v1/support/requests", produces = MediaType.APPLICATION_JSON_VALUE)
    List<Summary> list(@AuthenticationPrincipal Jwt jwt) {
        return requests.list(caller.require(jwt).id());
    }

    @Operation(operationId = "countUnreadSupportRequests")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "503", description = "support-disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/api/v1/support/requests/unread-count", produces = MediaType.APPLICATION_JSON_VALUE)
    Count unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new Count(requests.unreadCount(caller.require(jwt).id()));
    }

    @Operation(operationId = "getSupportRequest")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "request-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "support-disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/api/v1/support/requests/{number}", produces = MediaType.APPLICATION_JSON_VALUE)
    Detail open(@AuthenticationPrincipal Jwt jwt, @PathVariable long number) {
        return requests.open(caller.require(jwt).id(), number);
    }

    @Operation(operationId = "replyToSupportRequest")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors), attachment-rejected",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "request-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "request-closed, request-state-changed, idempotency-key-reused",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "support-disabled, attachments-unavailable (uploadIds sent)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/api/v1/support/requests/{number}/messages", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Detail> reply(@AuthenticationPrincipal Jwt jwt, @PathVariable long number,
            @Parameter(schema = @Schema(format = "uuid"))
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody ReplyRequest body) {
        UUID memberId = caller.require(jwt).id();
        Detail replied = requests.reply(memberId, number, body.body(), body.uploadIds(),
                IdempotencyGuard.key(idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(replied);
    }

    @Operation(operationId = "cancelSupportRequest")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "request-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "request-closed, transition-not-allowed, request-state-changed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "support-disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/api/v1/support/requests/{number}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    Detail cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long number) {
        return requests.cancel(caller.require(jwt).id(), number);
    }

    @Operation(operationId = "confirmSupportRequest")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "request-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "request-closed, transition-not-allowed, request-state-changed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "support-disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/api/v1/support/requests/{number}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    Detail confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable long number) {
        return requests.confirm(caller.require(jwt).id(), number);
    }
}
