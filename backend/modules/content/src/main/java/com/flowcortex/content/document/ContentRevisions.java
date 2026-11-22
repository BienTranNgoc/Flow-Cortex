package com.flowcortex.content.document;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.section.DocumentRegistry;
import com.flowcortex.content.web.ContentProblemException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.user.api.UserDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads of a document's snapshots: all of them for staff, and only publications of a document registered
 * with a public history for anyone. A document that is not public answers an empty page and a 404 detail,
 * identically for an unregistered slug.
 */
@Service
@Transactional(readOnly = true)
public class ContentRevisions {

    /** One snapshot in a staff list. */
    public record RevisionSummary(UUID id, String kind, int version, String authorEmail, Instant createdAt) {
    }

    /** One snapshot in full. */
    public record RevisionDetail(UUID id, String kind, int version, JsonNode sections, JsonNode seoMeta,
            String authorEmail, Instant createdAt) {
    }

    /** One publication in the public history list. */
    public record PublicationSummary(UUID revisionId, Instant publishedAt) {
    }

    /** One publication in full. */
    public record PublicationDetail(UUID revisionId, Instant publishedAt, String slug, JsonNode sections,
            JsonNode seoMeta) {
    }

    private final DocumentRegistry registry;
    private final ContentRevisionRepository revisions;
    private final UserDirectory users;
    private final JsonMapper json;

    ContentRevisions(DocumentRegistry registry, ContentRevisionRepository revisions, UserDirectory users,
            JsonMapper json) {
        this.registry = registry;
        this.revisions = revisions;
        this.users = users;
        this.json = json;
    }

    /** A page of snapshots, newest first, with the author emails of the page resolved in one lookup. */
    public PageView<RevisionSummary> page(String slug, int page, int size) {
        registered(slug);
        Page<ContentRevision> found = revisions.findBySlugOrderByCreatedAtDescIdDesc(slug, PageRequest.of(page, size));
        Map<UUID, String> emails = users.findEmailsByIds(found.map(ContentRevision::authorId).toSet());
        return new PageView<>(found.map(revision -> new RevisionSummary(revision.id(), revision.kind(),
                revision.version(), emails.get(revision.authorId()), revision.createdAt())).toList(),
                page, size, found.getTotalElements());
    }

    public RevisionDetail detail(String slug, UUID id) {
        registered(slug);
        ContentRevision revision = revisions.findByIdAndSlug(id, slug)
                .orElseThrow(ContentProblemException::documentNotFound);
        String email = users.findEmailsByIds(List.of(revision.authorId())).get(revision.authorId());
        return new RevisionDetail(revision.id(), revision.kind(), revision.version(),
                json.readTree(revision.sections()), json.readTree(revision.seoMeta()), email, revision.createdAt());
    }

    /** The publications of a public-history document, newest first; empty for any other slug. */
    public PageView<PublicationSummary> publicPage(String slug, int page, int size) {
        if (!hasPublicHistory(slug)) {
            return new PageView<>(List.of(), page, size, 0);
        }
        Page<ContentRevision> found = revisions.findBySlugAndKindOrderByCreatedAtDescIdDesc(slug,
                ContentRevision.PUBLICATION, PageRequest.of(page, size));
        return new PageView<>(found.map(revision -> new PublicationSummary(revision.id(), revision.createdAt())).toList(),
                page, size, found.getTotalElements());
    }

    public PublicationDetail publicDetail(String slug, UUID id) {
        if (!hasPublicHistory(slug)) {
            throw ContentProblemException.documentNotFound();
        }
        ContentRevision revision = revisions.findByIdAndSlugAndKind(id, slug, ContentRevision.PUBLICATION)
                .orElseThrow(ContentProblemException::documentNotFound);
        return new PublicationDetail(revision.id(), revision.createdAt(), slug, json.readTree(revision.sections()),
                json.readTree(revision.seoMeta()));
    }

    private void registered(String slug) {
        registry.find(slug).orElseThrow(ContentProblemException::documentNotFound);
    }

    private boolean hasPublicHistory(String slug) {
        Optional<ContentDocumentDefinition> definition = registry.find(slug);
        return definition.isPresent() && definition.get().publicHistory();
    }
}
