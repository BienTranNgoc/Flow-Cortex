package com.flowcortex.content.web;

import com.flowcortex.content.api.LocalizedText;
import com.flowcortex.content.legal.LegalIndexResolver;
import com.flowcortex.content.legal.LegalIndexResolver.Resolution;
import com.flowcortex.content.legal.LegalIndexResolver.ResolvedEntry;
import com.flowcortex.content.legal.UnresolvedLegalItems;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/content/legal-index} — the legal documents the member app links to, in display order; no token
 * needed. Cached like the published copies it reads.
 */
@RestController
@RequestMapping("/api/v1/content/legal-index")
@SecurityRequirements
class PublicLegalIndexController {

    private static final Logger LOG = LoggerFactory.getLogger(PublicLegalIndexController.class);

    private final LegalIndexResolver resolver;
    private final UnresolvedLegalItems unresolvedItems;

    PublicLegalIndexController(LegalIndexResolver resolver, UnresolvedLegalItems unresolvedItems) {
        this.resolver = resolver;
        this.unresolvedItems = unresolvedItems;
    }

    @Schema(name = "LegalIndexEntry")
    record Entry(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The document slug, e.g. legal-privacy")
            String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The member page path, e.g. /legal/privacy")
            String path,
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalizedText label) {

        static Entry of(ResolvedEntry entry) {
            return new Entry(entry.slug(), entry.path(), entry.label());
        }
    }

    @Schema(name = "LegalIndex")
    record LegalIndexResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<Entry> items) {
    }

    @Operation(operationId = "getLegalIndex")
    @ApiResponse(responseCode = "200")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LegalIndexResponse> legalIndex() {
        Resolution resolution = resolver.resolve();
        for (String slug : resolution.unresolved()) {
            if (unresolvedItems.shouldWarn(slug)) {
                LOG.warn("legal_index_item_unresolved slug={}", slug);
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, PublicContentDocumentsController.CACHE_CONTROL)
                .body(new LegalIndexResponse(resolution.items().stream().map(Entry::of).toList()));
    }
}
