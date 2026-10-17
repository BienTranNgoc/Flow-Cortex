package com.flowcortex.workflow.api;

/** Confirmed product data snapshotted when a Product Video run starts. */
public record ProductVideoDraft(String productName, String description, String cta, String price,
        String promotion, String aspectRatio) {
}
