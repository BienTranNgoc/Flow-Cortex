package com.flowcortex.content.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of a document: the editable {@code draft} or the {@code published} copy of a slug. The version
 * starts at 1 on insert and is advanced only by Hibernate's optimistic locking.
 */
@Entity
@Table(name = "content_document")
class ContentDocument {

    static final String DRAFT = "draft";
    static final String PUBLISHED = "published";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "slug", nullable = false, updatable = false, length = 64)
    private String slug;

    @Column(name = "status", nullable = false, updatable = false, length = 16)
    private String status;

    // The default equals Hibernate's version seed, so an explicit 1 on a new row still counts as transient.
    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sections", nullable = false)
    private String sections;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seo_meta", nullable = false)
    private String seoMeta;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentDocument() {
    }

    private ContentDocument(String slug, String status, String sections, String seoMeta, Instant publishedAt,
            UUID editor, Instant now) {
        this.id = UUID.randomUUID();
        this.slug = slug;
        this.status = status;
        this.version = 1;
        this.sections = sections;
        this.seoMeta = seoMeta;
        this.publishedAt = publishedAt;
        this.updatedBy = editor;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static ContentDocument newDraft(String slug, String sections, String seoMeta, UUID editor, Instant now) {
        return new ContentDocument(slug, DRAFT, sections, seoMeta, null, editor, now);
    }

    static ContentDocument newPublication(ContentDocument draft, UUID publisher, Instant now) {
        return new ContentDocument(draft.slug, PUBLISHED, draft.sections, draft.seoMeta, now, publisher, now);
    }

    void replaceDraft(String newSections, String newSeoMeta, UUID editor, Instant now) {
        this.sections = newSections;
        this.seoMeta = newSeoMeta;
        this.updatedBy = editor;
        this.updatedAt = now;
    }

    void republishFrom(ContentDocument draft, UUID publisher, Instant now) {
        this.sections = draft.sections;
        this.seoMeta = draft.seoMeta;
        this.publishedAt = now;
        this.updatedBy = publisher;
        this.updatedAt = now;
    }

    UUID id() {
        return id;
    }

    String slug() {
        return slug;
    }

    String status() {
        return status;
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

    Instant publishedAt() {
        return publishedAt;
    }

    UUID updatedBy() {
        return updatedBy;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
