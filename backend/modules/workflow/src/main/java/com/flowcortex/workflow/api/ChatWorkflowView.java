package com.flowcortex.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * A workflow as the chat assistant sees it. {@code inputKind} is {@code text}, {@code image} or
 * {@code product_video}; {@code estimatedCredits} is {@code null} while credits are switched off.
 */
public record ChatWorkflowView(UUID id, String name, List<String> stepTypes, String inputKind,
        Long estimatedCredits) {
}
