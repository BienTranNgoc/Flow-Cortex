package com.flowcortex.workflow.web;

import jakarta.validation.constraints.Size;

/** Request body to regenerate the MC keyframe image while paused at Checkpoint 2. */
public record RegenerateKeyframeRequest(
        @Size(max = 64) String backgroundPresetId,
        @Size(max = 500) String sceneNote,
        Boolean acknowledgeCost) {
}
