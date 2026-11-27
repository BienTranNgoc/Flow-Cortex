package com.flowcortex.content.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The publications of a document registered with a public history, for modules that must show which text was
 * in force at a moment. Every method answers empty for a slug that is not such a document.
 */
public interface PublicationIndex {

    /** One publication: its revision id and the time it was made. */
    record PublicationRef(UUID id, Instant publishedAt) {
    }

    /** A publication's text; {@code revisionId} is null for the live copy. */
    record PublicationText(UUID revisionId, Instant publishedAt, String slug, JsonNode sections, JsonNode seoMeta) {
    }

    /** The document's publications, newest first. */
    List<PublicationRef> publications(String slug);

    Optional<PublicationText> publication(String slug, UUID id);

    /** The copy published now, with its publication time; empty when the slug was never published. */
    Optional<PublicationText> live(String slug);
}
