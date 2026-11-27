package com.flowcortex.llm.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One model row under a capability, keyed by the capability and the vendor's model id. Rows are created by a
 * native insert on {@link CatalogModelRepository} so two concurrent creates of one key cannot overwrite each
 * other; this entity serves reads and whole-row replacement.
 */
@Entity
@Table(name = "model_catalog")
@IdClass(CatalogModel.Key.class)
class CatalogModel {

    /** Composite primary key of {@link CatalogModel}. */
    static class Key implements Serializable {

        private String capability;
        private String modelKey;

        /** Required by JPA. */
        protected Key() {
        }

        Key(String capability, String modelKey) {
            this.capability = capability;
            this.modelKey = modelKey;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(capability, key.capability)
                    && Objects.equals(modelKey, key.modelKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(capability, modelKey);
        }
    }

    @Id
    @Column(name = "capability", nullable = false, updatable = false)
    private String capability;

    @Id
    @Column(name = "model_key", nullable = false, updatable = false)
    private String modelKey;

    @Column(name = "provider_key", nullable = false)
    private String providerKey;

    @Column(name = "vendor_label")
    private String vendorLabel;

    @Column(name = "maker_label")
    private String makerLabel;

    @Column(name = "mode", nullable = false)
    private String mode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "not_implemented", nullable = false)
    private boolean notImplemented;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CatalogModel() {
    }

    String capability() {
        return capability;
    }

    String modelKey() {
        return modelKey;
    }

    String providerKey() {
        return providerKey;
    }

    String vendorLabel() {
        return vendorLabel;
    }

    String makerLabel() {
        return makerLabel;
    }

    String mode() {
        return mode;
    }

    boolean enabled() {
        return enabled;
    }

    boolean notImplemented() {
        return notImplemented;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    /** Replaces every field but the two keys and the creation time. */
    void replace(String newProviderKey, String newVendorLabel, String newMakerLabel, String newMode,
            boolean newEnabled, boolean newNotImplemented, Instant now) {
        this.providerKey = newProviderKey;
        this.vendorLabel = newVendorLabel;
        this.makerLabel = newMakerLabel;
        this.mode = newMode;
        this.enabled = newEnabled;
        this.notImplemented = newNotImplemented;
        this.updatedAt = now;
    }
}
