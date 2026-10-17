package com.flowcortex.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@IdClass(MediaAssetBindingId.class)
@Table(name = "media_asset_binding")
public class MediaAssetBinding {

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Id
    @Column(name = "binding_key", nullable = false, updatable = false)
    private String bindingKey;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "position", nullable = false, updatable = false)
    private short position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MediaAssetBinding() {
    }

    public MediaAssetBinding(UUID runId, String bindingKey, UUID assetId, short position, Instant createdAt) {
        this.runId = runId;
        this.bindingKey = bindingKey;
        this.assetId = assetId;
        this.position = position;
        this.createdAt = createdAt;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getBindingKey() {
        return bindingKey;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public short getPosition() {
        return position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
