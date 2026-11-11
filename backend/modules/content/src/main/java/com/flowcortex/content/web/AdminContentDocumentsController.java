package com.flowcortex.content.web;

import com.flowcortex.content.document.ContentDocumentService;
import com.flowcortex.content.document.ContentRevisions;
import com.flowcortex.content.web.ContentDocumentResponses.ContentSection;
import com.flowcortex.content.web.ContentDocumentResponses.ContentSeoMeta;
import com.flowcortex.content.web.ContentDocumentResponses.DocumentSummaryResponse;
import com.flowcortex.content.web.ContentDocumentResponses.DraftResponse;
import com.flowcortex.content.web.ContentDocumentResponses.PublishedResponse;
import com.flowcortex.content.web.ContentDocumentResponses.RevisionResponse;
import com.flowcortex.content.web.ContentDocumentResponses.RevisionSummaryResponse;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/v1/admin/content/documents} — staff read a document's draft and snapshots, save the draft with
 * its version token and publish it. The permission each route needs is declared on its handler.
 */
@RestController
@RequestMapping("/api/v1/admin/content/documents")
class AdminContentDocumentsController {

    private static final int MAX_PAGE = 100_000;
    private static final int MAX_PAGE_SIZE = 100;

    private final ContentDocumentService documents;
    private final ContentRevisions revisions;
    private final ContentCaller caller;

    AdminContentDocumentsController(ContentDocumentService documents, ContentRevisions revisions,
            ContentCaller caller) {
        this.documents = documents;
        this.revisions = revisions;
        this.caller = caller;
    }

    @Schema(name = "SaveContentDocumentDraftRequest")
    record SaveRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The draft version the editor loaded; 0 when none")
            @NotNull @Min(0) Integer version,
            @Schema(implementation = ContentSection[].class, requiredMode = RequiredMode.REQUIRED) JsonNode sections,
            @Schema(implementation = ContentSeoMeta.class, requiredMode = RequiredMode.REQUIRED) JsonNode seoMeta) {
    }

    @Schema(name = "PublishContentDocumentRequest")
    record PublishRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The draft version the editor reviewed")
            @NotNull @Min(0) Integer version) {
    }

    @Operation(operationId = "listContentDocuments")
    @RequirePermission(Permission.CONTENT_DOCUMENT_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<DocumentSummaryResponse> list() {
        return documents.list().stream().map(DocumentSummaryResponse::of).toList();
    }

    @Operation(operationId = "getContentDocumentDraft")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "document-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_DOCUMENT_VIEW)
    @GetMapping(value = "/{slug}", produces = MediaType.APPLICATION_JSON_VALUE)
    DraftResponse draft(@PathVariable String slug) {
        return DraftResponse.of(documents.draft(slug));
    }

    @Operation(operationId = "saveContentDocumentDraft")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "document-invalid (errors by field path), validation-failed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "document-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "version-conflict (currentVersion)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_DOCUMENT_MANAGE)
    @PutMapping(value = "/{slug}", produces = MediaType.APPLICATION_JSON_VALUE)
    DraftResponse save(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug,
            @Valid @RequestBody SaveRequest body) {
        return DraftResponse.of(documents.saveDraft(slug, caller.require(jwt), body.version(), body.sections(),
                body.seoMeta()));
    }

    @Operation(operationId = "publishContentDocument")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "document-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "version-conflict (currentVersion), nothing-to-publish",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "document-incomplete (fields)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_DOCUMENT_MANAGE)
    @PostMapping(value = "/{slug}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    PublishedResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug,
            @Valid @RequestBody PublishRequest body) {
        return PublishedResponse.of(documents.publish(slug, caller.require(jwt), body.version()));
    }

    @Operation(operationId = "listContentDocumentRevisions")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "document-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_DOCUMENT_VIEW)
    @GetMapping(value = "/{slug}/revisions", produces = MediaType.APPLICATION_JSON_VALUE)
    PageView<RevisionSummaryResponse> revisions(@PathVariable String slug,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return revisions.page(slug, page, size).map(RevisionSummaryResponse::of);
    }

    @Operation(operationId = "getContentDocumentRevision")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "document-not-found (also a snapshot of another document or a malformed id)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_DOCUMENT_VIEW)
    @GetMapping(value = "/{slug}/revisions/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    RevisionResponse revision(@PathVariable String slug, @PathVariable String id) {
        return RevisionResponse.of(revisions.detail(slug, SnapshotIds.parse(id)));
    }
}
