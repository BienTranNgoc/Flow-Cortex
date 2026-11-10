package com.flowcortex.llm.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One language model the console manages, keyed by the provider's model id. Rows are created by a native
 * insert on {@link LlmModelRepository} so two concurrent creates of one id cannot overwrite each other;
 * this entity serves reads and whole-entry replacement.
 */
@Entity
@Table(name = "llm_model")
class LlmModel {

    @Id
    @Column(name = "model_key", nullable = false, updatable = false, length = 128)
    private String modelKey;

    @Column(name = "provider_key", nullable = false, length = 32)
    private String providerKey;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "max_output_tokens", nullable = false)
    private int maxOutputTokens;

    @Column(name = "supports_vision", nullable = false)
    private boolean supportsVision;

    @Column(name = "supports_audio", nullable = false)
    private boolean supportsAudio;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LlmModel() {
    }

    String modelKey() {
        return modelKey;
    }

    String providerKey() {
        return providerKey;
    }

    String displayName() {
        return displayName;
    }

    int maxOutputTokens() {
        return maxOutputTokens;
    }

    boolean supportsVision() {
        return supportsVision;
    }

    boolean supportsAudio() {
        return supportsAudio;
    }

    boolean active() {
        return active;
    }

    short displayOrder() {
        return displayOrder;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    /** Replaces every editable field; the id and the creation time stay. */
    void replace(String newProviderKey, String newDisplayName, int newMaxOutputTokens, boolean newSupportsVision,
            boolean newSupportsAudio, boolean newActive, short newDisplayOrder, Instant now) {
        this.providerKey = newProviderKey;
        this.displayName = newDisplayName;
        this.maxOutputTokens = newMaxOutputTokens;
        this.supportsVision = newSupportsVision;
        this.supportsAudio = newSupportsAudio;
        this.active = newActive;
        this.displayOrder = newDisplayOrder;
        this.updatedAt = now;
    }
}
