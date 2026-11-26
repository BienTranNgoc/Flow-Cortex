package com.flowcortex.llm.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One capability (step type) of the catalog. Rows are written by migrations; the console replaces only the
 * display fields (category, group, icon).
 */
@Entity
@Table(name = "capability_catalog")
class CatalogCapability {

    @Id
    @Column(name = "capability", nullable = false, updatable = false)
    private String capability;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "ui_group", nullable = false)
    private String uiGroup;

    @Column(name = "ui_icon")
    private String uiIcon;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CatalogCapability() {
    }

    String capability() {
        return capability;
    }

    String category() {
        return category;
    }

    String uiGroup() {
        return uiGroup;
    }

    String uiIcon() {
        return uiIcon;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    /** Replaces every display field; the key and the creation time stay. */
    void replace(String newCategory, String newUiGroup, String newUiIcon, Instant now) {
        this.category = newCategory;
        this.uiGroup = newUiGroup;
        this.uiIcon = newUiIcon;
        this.updatedAt = now;
    }
}
