package com.flowcortex.support.kb;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A knowledge-base article. Only {@code published} articles are visible outside the admin console;
 * {@code searchText} is rewritten from title and body on every change, and archiving frees the
 * article's "Bắt đầu" position.
 */
@Entity
@Table(name = "support_article")
class Article {

    static final String DRAFT = "draft";
    static final String PUBLISHED = "published";
    static final String ARCHIVED = "archived";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "slug", nullable = false, columnDefinition = "text")
    private String slug;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "search_text", nullable = false, columnDefinition = "text")
    private String searchText;

    @Column(name = "status", nullable = false, columnDefinition = "text")
    private String status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "featured_position")
    private Short featuredPosition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Article() {
    }

    Article(UUID groupId, String slug, String title, String body, Short featuredPosition, Instant now) {
        this.id = UUID.randomUUID();
        this.status = DRAFT;
        this.createdAt = now;
        edit(groupId, slug, title, body, featuredPosition, now);
    }

    void edit(UUID newGroupId, String newSlug, String newTitle, String newBody, Short newFeaturedPosition,
            Instant now) {
        this.groupId = newGroupId;
        this.slug = newSlug;
        this.title = newTitle;
        this.body = newBody;
        this.searchText = SearchText.normalize(newTitle + "\n" + newBody);
        this.featuredPosition = newFeaturedPosition;
        this.updatedAt = now;
    }

    void publish(Instant now) {
        if (!PUBLISHED.equals(status)) {
            this.status = PUBLISHED;
            this.publishedAt = now;
            this.updatedAt = now;
        }
    }

    void archive(Instant now) {
        if (!ARCHIVED.equals(status)) {
            this.status = ARCHIVED;
            this.featuredPosition = null;
            this.updatedAt = now;
        }
    }

    boolean isPublished() {
        return PUBLISHED.equals(status);
    }

    boolean isArchived() {
        return ARCHIVED.equals(status);
    }

    UUID getId() {
        return id;
    }

    UUID getGroupId() {
        return groupId;
    }

    String getSlug() {
        return slug;
    }

    String getTitle() {
        return title;
    }

    String getBody() {
        return body;
    }

    String getStatus() {
        return status;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    Short getFeaturedPosition() {
        return featuredPosition;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
