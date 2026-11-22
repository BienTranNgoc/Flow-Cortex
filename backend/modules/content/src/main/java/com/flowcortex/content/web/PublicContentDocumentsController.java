package com.flowcortex.content.web;

import com.flowcortex.content.document.ContentDocumentService;
import com.flowcortex.content.document.ContentRevisions;
import com.flowcortex.content.web.ContentDocumentResponses.PublicationResponse;
import com.flowcortex.content.web.ContentDocumentResponses.PublicationSummaryResponse;
import com.flowcortex.content.web.ContentDocumentResponses.PublishedResponse;
import com.flowcortex.shared.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/content/documents} — the published copy of a document and, for documents registered with a
 * public history, its earlier publications; no token needed and no route here serves a draft. A 200 may be
 * shown by a browser for at most one further response within ten seconds; a 404 is never stored.
 */
@RestController
@RequestMapping("/api/v1/content/documents")
@SecurityRequirements
class PublicContentDocumentsController {

    static final String CACHE_CONTROL = "public, max-age=0, stale-while-revalidate=10";

    private static final int MAX_PAGE = 100_000;
    private static final int MAX_PAGE_SIZE = 100;

    private final ContentDocumentService documents;
    private final ContentRevisions revisions;

    PublicContentDocumentsController(ContentDocumentService documents, ContentRevisions revisions) {
        this.documents = documents;
        this.revisions = revisions;
    }

    @Operation(operationId = "getPublishedContentDocument")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "document-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/{slug}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublishedResponse> published(@PathVariable String slug) {
        return cacheable(PublishedResponse.of(documents.published(slug)));
    }

    @Operation(operationId = "listContentDocumentHistory")
    @ApiResponse(responseCode = "200")
    @GetMapping(value = "/{slug}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<PublicationSummaryResponse>> history(@PathVariable String slug,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return cacheable(revisions.publicPage(slug, page, size).map(PublicationSummaryResponse::of));
    }

    @Operation(operationId = "getContentDocumentPublication")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "document-not-found (also a draft snapshot or a malformed id)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/{slug}/history/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublicationResponse> publication(@PathVariable String slug, @PathVariable String id) {
        return cacheable(PublicationResponse.of(revisions.publicDetail(slug, SnapshotIds.parse(id))));
    }

    private static <T> ResponseEntity<T> cacheable(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
    }
}
