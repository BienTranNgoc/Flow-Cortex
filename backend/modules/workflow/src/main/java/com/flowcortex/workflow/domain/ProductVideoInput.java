package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable product metadata snapshot associated one-to-one with its workflow run. */
@Entity
@Table(name = "product_video_input")
public class ProductVideoInput {

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "product_name", nullable = false, updatable = false)
    private String productName;

    @Column(name = "description", nullable = false, updatable = false)
    private String description;

    @Column(name = "cta", nullable = false, updatable = false)
    private String cta;

    @Column(name = "price", updatable = false)
    private String price;

    @Column(name = "promotion", updatable = false)
    private String promotion;

    @Column(name = "aspect_ratio", nullable = false, updatable = false)
    private String aspectRatio;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductVideoInput() {
    }

    public ProductVideoInput(UUID runId, String productName, String description, String cta, String price,
            String promotion, String aspectRatio, Instant createdAt) {
        this.runId = runId;
        this.productName = productName;
        this.description = description;
        this.cta = cta;
        this.price = price;
        this.promotion = promotion;
        this.aspectRatio = aspectRatio;
        this.createdAt = createdAt;
    }

    public UUID getRunId() {
        return runId;
    }
}
