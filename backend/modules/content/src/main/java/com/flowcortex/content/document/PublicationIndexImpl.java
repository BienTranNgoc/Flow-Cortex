package com.flowcortex.content.document;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.PublicationIndex;
import com.flowcortex.content.section.DocumentRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
@Transactional(readOnly = true)
class PublicationIndexImpl implements PublicationIndex {

    private final DocumentRegistry registry;
    private final ContentRevisionRepository revisions;
    private final PublishedDocuments published;
    private final JsonMapper json;

    PublicationIndexImpl(DocumentRegistry registry, ContentRevisionRepository revisions, PublishedDocuments published,
            JsonMapper json) {
        this.registry = registry;
        this.revisions = revisions;
        this.published = published;
        this.json = json;
    }

    @Override
    public List<PublicationRef> publications(String slug) {
        if (!hasPublicHistory(slug)) {
            return List.of();
        }
        return revisions.findBySlugAndKindOrderByCreatedAtDescIdDesc(slug, ContentRevision.PUBLICATION).stream()
                .map(revision -> new PublicationRef(revision.id(), revision.createdAt()))
                .toList();
    }

    @Override
    public Optional<PublicationText> publication(String slug, UUID id) {
        if (!hasPublicHistory(slug)) {
            return Optional.empty();
        }
        return revisions.findByIdAndSlugAndKind(id, slug, ContentRevision.PUBLICATION)
                .map(revision -> new PublicationText(revision.id(), revision.createdAt(), slug,
                        json.readTree(revision.sections()), json.readTree(revision.seoMeta())));
    }

    @Override
    public Optional<PublicationText> live(String slug) {
        if (!hasPublicHistory(slug)) {
            return Optional.empty();
        }
        return published.find(slug)
                .map(copy -> new PublicationText(null, copy.publishedAt(), slug, copy.sections(), copy.seoMeta()));
    }

    private boolean hasPublicHistory(String slug) {
        Optional<ContentDocumentDefinition> definition = registry.find(slug);
        return definition.isPresent() && definition.get().publicHistory();
    }
}
