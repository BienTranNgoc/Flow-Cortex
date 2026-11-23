package com.flowcortex.content.media;

import com.flowcortex.media.api.StoredContentMedia;
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
import org.springframework.data.domain.Persistable;

/**
 * One library item: a kept copy at {@code content/<id>} of the media bucket. Nothing updates one. Public only
 * because {@link ContentMediaRepository}, which other packages of the module read ids through, is a proxied
 * public interface over it.
 */
@Entity
@Immutable
@Table(name = "content_media")
public class ContentMedia implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "kind", nullable = false, updatable = false, length = 8)
    private String kind;

    @Column(name = "content_type", nullable = false, updatable = false, length = 64)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "bucket", nullable = false, updatable = false, length = 63)
    private String bucket;

    @Column(name = "object_key", nullable = false, updatable = false, length = 512)
    private String objectKey;

    @Column(name = "file_name", nullable = false, updatable = false, length = 255)
    private String fileName;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh = true;

    protected ContentMedia() {
    }

    ContentMedia(UUID id, StoredContentMedia stored, String fileName, UUID uploadedBy, Instant now) {
        this.id = id;
        this.kind = stored.kind();
        this.contentType = stored.contentType();
        this.sizeBytes = stored.sizeBytes();
        this.bucket = stored.bucket();
        this.objectKey = stored.objectKey();
        this.fileName = fileName;
        this.uploadedBy = uploadedBy;
        this.createdAt = now;
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

    String contentType() {
        return contentType;
    }

    long sizeBytes() {
        return sizeBytes;
    }

    String bucket() {
        return bucket;
    }

    String objectKey() {
        return objectKey;
    }

    String fileName() {
        return fileName;
    }

    UUID uploadedBy() {
        return uploadedBy;
    }

    Instant createdAt() {
        return createdAt;
    }
}
