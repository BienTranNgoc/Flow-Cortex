package com.flowcortex.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_asset")
public class MediaAsset {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "bucket", nullable = false, updatable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "bytes", nullable = false)
    private long bytes;

    @Column(name = "checksum")
    private String checksum;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "source_run_step_id", updatable = false)
    private UUID sourceRunStepId;

    @Column(name = "source_attempt", updatable = false)
    private Short sourceAttempt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MediaAsset() {
    }

    public MediaAsset(UUID id, UUID ownerId, String kind, String bucket, String objectKey, String mimeType,
            long bytes, String checksum, Instant createdAt) {
        this(id, ownerId, kind, bucket, objectKey, mimeType, bytes, checksum, null, null, createdAt);
    }

    public MediaAsset(UUID id, UUID ownerId, String kind, String bucket, String objectKey, String mimeType,
            long bytes, String checksum, UUID sourceRunStepId, Short sourceAttempt, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.kind = kind;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.mimeType = mimeType;
        this.bytes = bytes;
        this.checksum = checksum;
        this.status = "ready";
        this.sourceRunStepId = sourceRunStepId;
        this.sourceAttempt = sourceAttempt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getKind() {
        return kind;
    }

    public String getBucket() {
        return bucket;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getBytes() {
        return bytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getSourceRunStepId() {
        return sourceRunStepId;
    }

    public Short getSourceAttempt() {
        return sourceAttempt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void refresh(String mimeType, long bytes, String checksum) {
        this.mimeType = mimeType;
        this.bytes = bytes;
        this.checksum = checksum;
        this.status = "ready";
    }
}
