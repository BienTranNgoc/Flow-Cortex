package com.flowcortex.content.web;

import com.flowcortex.content.api.LocalizedText;
import com.flowcortex.content.document.ContentDocumentService.DocumentSummary;
import com.flowcortex.content.document.ContentDocumentService.DraftView;
import com.flowcortex.content.document.ContentRevisions.PublicationDetail;
import com.flowcortex.content.document.ContentRevisions.PublicationSummary;
import com.flowcortex.content.document.ContentRevisions.RevisionDetail;
import com.flowcortex.content.document.ContentRevisions.RevisionSummary;
import com.flowcortex.content.document.PublishedDocuments.PublishedCopy;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The request and response bodies of the document routes. */
final class ContentDocumentResponses {

    private ContentDocumentResponses() {
    }

    /** The contract shape of one entry of {@code sections}; the API itself carries the list as raw JSON. */
    @Schema(name = "ContentSection")
    record ContentSection(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The section type, e.g. richText or media")
            String type,
            @Schema(requiredMode = RequiredMode.REQUIRED) int schemaVersion,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The fields of the section type; text fields are {vi, en}")
            Map<String, Object> data) {
    }

    /** The contract shape of {@code seoMeta}; the API itself carries it as raw JSON. */
    @Schema(name = "ContentSeoMeta")
    record ContentSeoMeta(
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalizedText title,
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalizedText description) {
    }

    @Schema(name = "ContentDocumentSummary")
    record DocumentSummaryResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalizedText title,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> sectionTypes,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean publicHistory,
            @Schema(requiredMode = RequiredMode.REQUIRED) int draftVersion,
            @Schema(nullable = true) Instant draftUpdatedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) int publishedVersion,
            @Schema(nullable = true) Instant publishedAt) {

        static DocumentSummaryResponse of(DocumentSummary summary) {
            return new DocumentSummaryResponse(summary.definition().slug(), summary.definition().title(),
                    summary.definition().sectionTypes(), summary.definition().publicHistory(),
                    summary.draftVersion(), summary.draftUpdatedAt(), summary.publishedVersion(),
                    summary.publishedAt());
        }
    }

    @Schema(name = "ContentDocumentDraft")
    record DraftResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED) int version,
            @Schema(implementation = ContentSection[].class, requiredMode = RequiredMode.REQUIRED) JsonNode sections,
            @Schema(implementation = ContentSeoMeta.class, requiredMode = RequiredMode.REQUIRED) JsonNode seoMeta,
            @Schema(nullable = true) Instant updatedAt,
            @Schema(nullable = true) String updatedByEmail) {

        static DraftResponse of(DraftView draft) {
            return new DraftResponse(draft.slug(), draft.version(), draft.sections(), draft.seoMeta(),
                    draft.updatedAt(), draft.updatedByEmail());
        }
    }

    @Schema(name = "ContentDocumentPublication")
    record PublishedResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED) int version,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant publishedAt,
            @Schema(implementation = ContentSection[].class, requiredMode = RequiredMode.REQUIRED) JsonNode sections,
            @Schema(implementation = ContentSeoMeta.class, requiredMode = RequiredMode.REQUIRED) JsonNode seoMeta) {

        static PublishedResponse of(PublishedCopy copy) {
            return new PublishedResponse(copy.slug(), copy.version(), copy.publishedAt(), copy.sections(),
                    copy.seoMeta());
        }
    }

    @Schema(name = "ContentDocumentRevisionSummary")
    record RevisionSummaryResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"draft", "publication"}) String kind,
            @Schema(requiredMode = RequiredMode.REQUIRED) int version,
            @Schema(nullable = true) String authorEmail,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {

        static RevisionSummaryResponse of(RevisionSummary revision) {
            return new RevisionSummaryResponse(revision.id(), revision.kind(), revision.version(),
                    revision.authorEmail(), revision.createdAt());
        }
    }

    @Schema(name = "ContentDocumentRevision")
    record RevisionResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"draft", "publication"}) String kind,
            @Schema(requiredMode = RequiredMode.REQUIRED) int version,
            @Schema(implementation = ContentSection[].class, requiredMode = RequiredMode.REQUIRED) JsonNode sections,
            @Schema(implementation = ContentSeoMeta.class, requiredMode = RequiredMode.REQUIRED) JsonNode seoMeta,
            @Schema(nullable = true) String authorEmail,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {

        static RevisionResponse of(RevisionDetail revision) {
            return new RevisionResponse(revision.id(), revision.kind(), revision.version(), revision.sections(),
                    revision.seoMeta(), revision.authorEmail(), revision.createdAt());
        }
    }

    @Schema(name = "ContentDocumentHistoryEntry")
    record PublicationSummaryResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID revisionId,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant publishedAt) {

        static PublicationSummaryResponse of(PublicationSummary publication) {
            return new PublicationSummaryResponse(publication.revisionId(), publication.publishedAt());
        }
    }

    @Schema(name = "ContentDocumentHistoryPublication")
    record PublicationResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID revisionId,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant publishedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(implementation = ContentSection[].class, requiredMode = RequiredMode.REQUIRED) JsonNode sections,
            @Schema(implementation = ContentSeoMeta.class, requiredMode = RequiredMode.REQUIRED) JsonNode seoMeta) {

        static PublicationResponse of(PublicationDetail publication) {
            return new PublicationResponse(publication.revisionId(), publication.publishedAt(), publication.slug(),
                    publication.sections(), publication.seoMeta());
        }
    }
}
