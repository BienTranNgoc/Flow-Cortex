package com.flowcortex.content.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * An append-only snapshot of a document: the draft a save replaced ({@code draft}) or what a publication
 * made live ({@code publication}). Nothing updates or deletes one.
 */
@Entity
@Immutable
@Table(name = "content_document_revision")
class ContentRevision implements Persistable<UUID> {

    static final String DRAFT = "draft";
    static final String PUBLICATION = "publication";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "slug", nullable = false, updatable = false, length = 64)
    private String slug;

    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private String kind;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sections", nullable = false, updatable = false)
    private String sections;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seo_meta", nullable = false, updatable = false)
    private String seoMeta;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh = true;

    protected ContentRevision() {
    }

    private ContentRevision(ContentDocument source, String kind, UUID author, Instant now) {
        this.id = UUID.randomUUID();
        this.slug = source.slug();
        this.kind = kind;
        this.version = source.version();
        this.sections = source.sections();
        this.seoMeta = source.seoMeta();
        this.authorId = author;
        this.createdAt = now;
    }

    /** The draft as it stands before a save replaces it, attributed to its last editor. */
    static ContentRevision ofDraft(ContentDocument draft, Instant now) {
        return new ContentRevision(draft, DRAFT, draft.updatedBy(), now);
    }

    /** The published copy as it stands after a publish, attributed to the publisher. */
    static ContentRevision ofPublication(ContentDocument published, UUID publisher, Instant now) {
        return new ContentRevision(published, PUBLICATION, publisher, now);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostPersist
    @PostLoad
    void markStored() {
        this.fresh = false;
    }

    UUID id() {
        return id;
    }

    String kind() {
        return kind;
    }

    int version() {
        return version;
    }

    String sections() {
        return sections;
    }

    String seoMeta() {
        return seoMeta;
    }

    UUID authorId() {
        return authorId;
    }

    Instant createdAt() {
        return createdAt;
    }
}
