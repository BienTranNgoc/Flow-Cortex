package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.Caller;
import com.flowcortex.workflow.application.ShareOrigin;
import com.flowcortex.workflow.application.WorkflowSharingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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
 * {@code /api/v1/workflows} sharing routes: the owner's visibility, share key, grants, access log and audit, and the
 * link view a share key opens. Only {@code by-key} is reachable without a token (see
 * {@link WorkflowShareSecurityConfig}); the rest run on the default authenticated chain.
 */
@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowSharingController {

    private static final String DEFAULT_PAGE_SIZE = "100";

    private final WorkflowSharingService sharingService;
    private final CurrentUserResolver currentUserResolver;

    WorkflowSharingController(WorkflowSharingService sharingService, CurrentUserResolver currentUserResolver) {
        this.sharingService = sharingService;
        this.currentUserResolver = currentUserResolver;
    }

    static ShareOrigin originOf(HttpServletRequest request) {
        return ShareOrigin.of(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "setWorkflowVisibility")
    @ResponseStatus(HttpStatus.OK)
    @PatchMapping(value = "/{id}/visibility", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ShareSettingsResponse> setVisibility(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SetVisibilityRequest request, HttpServletRequest http) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ShareSettingsResponse.from(sharingService.setVisibility(Caller.member(callerId), id,
                request.target(), request.expiresAt(), originOf(http))));
    }

    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "getWorkflowSharing")
    @ResponseStatus(HttpStatus.OK)
    @GetMapping(value = "/{id}/sharing", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ShareSettingsResponse> getSharing(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ShareSettingsResponse.from(sharingService.sharingSettings(Caller.member(callerId),
                id)));
    }

    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "regenerateWorkflowShareKey")
    @ResponseStatus(HttpStatus.OK)
    @PostMapping(value = "/{id}/regenerate-key", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ShareSettingsResponse> regenerateKey(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            HttpServletRequest http) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ShareSettingsResponse.from(sharingService.regenerateKey(Caller.member(callerId), id,
                originOf(http))));
    }

    /** Token optional: a signed-in reader is named in the access log, an anonymous one is hashed by network. */
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "410", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "getWorkflowByShareKey")
    @ResponseStatus(HttpStatus.OK)
    @GetMapping(value = "/by-key/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SharedWorkflowResponse> getByKey(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
            HttpServletRequest http) {
        UUID callerId = jwt == null ? null : currentUserResolver.require(jwt);
        return ResponseEntity.ok(SharedWorkflowResponse.from(sharingService.sharedView(callerId, key,
                originOf(http))));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "listWorkflowLinkGrants")
    @ResponseStatus(HttpStatus.OK)
    @GetMapping(value = "/{id}/link-grants", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LinkGrantPageResponse> listLinkGrants(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int limit, @RequestParam(required = false) String cursor) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(LinkGrantPageResponse.from(sharingService.linkGrants(Caller.member(callerId), id,
                limit, cursor)));
    }

    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "revokeWorkflowLinkGrant")
    @ResponseStatus(HttpStatus.OK)
    @DeleteMapping(value = "/{id}/link-grants/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RevokedResponse> revokeLinkGrant(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID userId) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(new RevokedResponse(sharingService.revokeGrant(Caller.member(callerId), id, userId)));
    }

    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "revokeAllWorkflowLinkGrants")
    @ResponseStatus(HttpStatus.OK)
    @DeleteMapping(value = "/{id}/link-grants", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RevokedResponse> revokeAllLinkGrants(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(new RevokedResponse(sharingService.revokeAllGrants(Caller.member(callerId), id)));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "getWorkflowAccessLog")
    @ResponseStatus(HttpStatus.OK)
    @GetMapping(value = "/{id}/access-log", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccessLogPageResponse> getAccessLog(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int limit, @RequestParam(required = false) String cursor) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(AccessLogPageResponse.from(sharingService.accessLog(Caller.member(callerId), id,
                limit, cursor)));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "getWorkflowVisibilityAudit")
    @ResponseStatus(HttpStatus.OK)
    @GetMapping(value = "/{id}/visibility-audit", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<VisibilityAuditPageResponse> getVisibilityAudit(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int limit,
            @RequestParam(required = false) String cursor) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(VisibilityAuditPageResponse.from(sharingService.visibilityAudit(
                Caller.member(callerId), id, limit, cursor)));
    }
}
