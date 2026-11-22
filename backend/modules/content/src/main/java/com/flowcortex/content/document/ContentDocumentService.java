package com.flowcortex.content.document;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.FieldError;
import com.flowcortex.content.document.PublishedDocuments.PublishedCopy;
import com.flowcortex.content.section.DocumentRegistry;
import com.flowcortex.content.section.DocumentValidator;
import com.flowcortex.content.section.PublishCompleteness;
import com.flowcortex.content.web.ContentProblemException;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Draft reads and writes with a version token, publication, and the published read. A write runs in its own
 * transaction so a lost race can be reported with the winner's version, which is read after the loser's
 * transaction has rolled back.
 */
@Service
public class ContentDocumentService {

    private static final String SLUG_STATUS_CONSTRAINT = "uq_content_document_slug_status";

    /** One row of the documents page; versions are 0 and times null while there is no draft or publication. */
    public record DocumentSummary(ContentDocumentDefinition definition, int draftVersion, Instant draftUpdatedAt,
            int publishedVersion, Instant publishedAt) {
    }

    /** The editable draft; version 0 with no sections when nothing was ever saved. */
    public record DraftView(String slug, int version, JsonNode sections, JsonNode seoMeta, Instant updatedAt,
            String updatedByEmail) {
    }

    private final DocumentRegistry registry;
    private final DocumentValidator validator;
    private final PublishCompleteness completeness;
    private final ContentDocumentRepository documents;
    private final ContentRevisionRepository revisions;
    private final MediaIds mediaIds;
    private final PublishedDocuments published;
    private final UserDirectory users;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ContentDocumentService(DocumentRegistry registry, DocumentValidator validator, PublishCompleteness completeness,
            ContentDocumentRepository documents, ContentRevisionRepository revisions, MediaIds mediaIds,
            PublishedDocuments published, UserDirectory users, JsonMapper json, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.validator = validator;
        this.completeness = completeness;
        this.documents = documents;
        this.revisions = revisions;
        this.mediaIds = mediaIds;
        this.published = published;
        this.users = users;
        this.json = json;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Every registered document in registration order, with its draft and publication versions. */
    @Transactional(readOnly = true)
    public List<DocumentSummary> list() {
        List<ContentDocumentDefinition> definitions = registry.all();
        Map<String, Map<String, ContentDocument>> bySlug = new HashMap<>();
        documents.findBySlugIn(definitions.stream().map(ContentDocumentDefinition::slug).toList())
                .forEach(row -> bySlug.computeIfAbsent(row.slug(), slug -> new HashMap<>()).put(row.status(), row));
        return definitions.stream().map(definition -> {
            Map<String, ContentDocument> rows = bySlug.getOrDefault(definition.slug(), Map.of());
            ContentDocument draft = rows.get(ContentDocument.DRAFT);
            ContentDocument live = rows.get(ContentDocument.PUBLISHED);
            return new DocumentSummary(definition,
                    draft == null ? 0 : draft.version(), draft == null ? null : draft.updatedAt(),
                    live == null ? 0 : live.version(), live == null ? null : live.publishedAt());
        }).toList();
    }

    /** The draft of a registered document; reading never creates a row. */
    @Transactional(readOnly = true)
    public DraftView draft(String slug) {
        definition(slug);
        return documents.findBySlugAndStatus(slug, ContentDocument.DRAFT)
                .map(this::view)
                .orElseGet(() -> new DraftView(slug, 0, json.createArrayNode(), emptySeoMeta(), null, null));
    }

    /**
     * Stores the draft when {@code version} is the stored draft's version (0 when none exists), first
     * snapshotting the draft it replaces.
     */
    public DraftView saveDraft(String slug, UUID callerId, int version, JsonNode sections, JsonNode seoMeta) {
        ContentDocumentDefinition definition = definition(slug);
        List<FieldError> errors = validator.validate(definition, sections, seoMeta,
                mediaIds.existing(validator.referencedMedia(definition, sections)));
        if (!errors.isEmpty()) {
            throw ContentProblemException.documentInvalid(errors);
        }
        String sectionsJson = sections.toString();
        String seoMetaJson = seoMeta.toString();
        ContentDocument saved = withConflictAsVersionConflict(slug, () -> transaction.execute(status -> {
            Instant now = clock.instant();
            Optional<ContentDocument> stored = documents.findBySlugAndStatus(slug, ContentDocument.DRAFT);
            if (stored.isEmpty()) {
                if (version != 0) {
                    throw ContentProblemException.versionConflict(0);
                }
                ContentDocument created = ContentDocument.newDraft(slug, sectionsJson, seoMetaJson, callerId, now);
                documents.persist(created);
                return created;
            }
            ContentDocument draft = stored.get();
            if (draft.version() != version) {
                throw ContentProblemException.versionConflict(draft.version());
            }
            revisions.save(ContentRevision.ofDraft(draft, now));
            draft.replaceDraft(sectionsJson, seoMetaJson, callerId, now);
            documents.flush();
            return draft;
        }));
        return view(saved);
    }

    /**
     * Replaces the published copy with the draft named by {@code version}, for both languages, and
     * snapshots the publication, in one transaction.
     */
    public PublishedCopy publish(String slug, UUID callerId, int version) {
        ContentDocumentDefinition definition = definition(slug);
        return withConflictAsVersionConflict(slug, () -> transaction.execute(status -> {
            ContentDocument draft = documents.findForUpdate(slug, ContentDocument.DRAFT)
                    .orElseThrow(ContentProblemException::nothingToPublish);
            if (draft.version() != version) {
                throw ContentProblemException.versionConflict(draft.version());
            }
            List<String> missing = completeness.missing(definition, json.readTree(draft.sections()),
                    json.readTree(draft.seoMeta()));
            if (!missing.isEmpty()) {
                throw ContentProblemException.documentIncomplete(missing);
            }
            Instant now = clock.instant();
            ContentDocument live = documents.findForUpdate(slug, ContentDocument.PUBLISHED).orElse(null);
            if (live == null) {
                live = ContentDocument.newPublication(draft, callerId, now);
                documents.persist(live);
            } else {
                live.republishFrom(draft, callerId, now);
                documents.flush();
            }
            revisions.save(ContentRevision.ofPublication(live, callerId, now));
            published.invalidateAfterCommit(slug);
            return PublishedCopy.of(live, json);
        }));
    }

    /** The published copy served to anyone; an unregistered or never-published slug is not found. */
    public PublishedCopy published(String slug) {
        definition(slug);
        return published.find(slug).orElseThrow(ContentProblemException::documentNotFound);
    }

    private ContentDocumentDefinition definition(String slug) {
        return registry.find(slug).orElseThrow(ContentProblemException::documentNotFound);
    }

    /**
     * A lost optimistic-lock race, or a concurrent first save of the same slug, is a stale token: the winner has
     * committed by the time the loser's exception arrives, so its version can be read.
     */
    private <T> T withConflictAsVersionConflict(String slug, Supplier<T> write) {
        try {
            return write.get();
        } catch (OptimisticLockingFailureException e) {
            throw ContentProblemException.versionConflict(storedDraftVersion(slug));
        } catch (DataIntegrityViolationException e) {
            if (!violatesSlugStatusKey(e)) {
                throw e;
            }
            throw ContentProblemException.versionConflict(storedDraftVersion(slug));
        }
    }

    private int storedDraftVersion(String slug) {
        return documents.findBySlugAndStatus(slug, ContentDocument.DRAFT).map(ContentDocument::version).orElse(0);
    }

    private static boolean violatesSlugStatusKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(SLUG_STATUS_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    private DraftView view(ContentDocument draft) {
        String email = users.findEmailsByIds(List.of(draft.updatedBy())).get(draft.updatedBy());
        return new DraftView(draft.slug(), draft.version(), json.readTree(draft.sections()),
                json.readTree(draft.seoMeta()), draft.updatedAt(), email);
    }

    private JsonNode emptySeoMeta() {
        var seoMeta = json.createObjectNode();
        for (String field : List.of("title", "description")) {
            var text = seoMeta.putObject(field);
            text.put("vi", "");
            text.put("en", "");
        }
        return seoMeta;
    }
}
