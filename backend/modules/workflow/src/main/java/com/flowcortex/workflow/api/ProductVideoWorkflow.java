package com.flowcortex.workflow.api;

import java.util.UUID;

/** The Product Video workflow, seeded by a migration under this fixed id. */
public final class ProductVideoWorkflow {

    public static final UUID ID = UUID.fromString("9aa35334-b672-4b6c-93e8-1a4c2cc14301");

    /** Length of the rendered video; speech longer than this would be cut off. Mirrors
     * {@code productVideoDurationSeconds} in contracts/limits.json. */
    public static final int VIDEO_DURATION_SECONDS = 10;

    private ProductVideoWorkflow() {
    }
}
