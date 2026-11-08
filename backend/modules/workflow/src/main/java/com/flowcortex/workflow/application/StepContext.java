package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.workflow.api.RunOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What an executor gets for one step: its identity, the fencing {@code attempt}, the resolved
 * input, the step definition's {@code config} JSON ({@code null} when unset), the input the
 * run step stored when its run started ({@code null} when none) and the options its run was
 * started with.
 *
 * <p>{@code storedOutputs} are recorded as the step's outputs when it completes; an inline executor
 * fills it by storing each object through {@link #store}.
 */
record StepContext(UUID runId, UUID stepId, int stepOrder, String type, short attempt, String input, String config,
        String storedInput, RunOptions options, List<MediaOutput> storedOutputs) {

    /** A context with the default run options and nothing stored yet. */
    StepContext(UUID runId, UUID stepId, int stepOrder, String type, short attempt, String input, String config,
            String storedInput) {
        this(runId, stepId, stepOrder, type, attempt, input, config, storedInput, RunOptions.DEFAULTS,
                new ArrayList<>());
    }

    /**
     * Stores {@code bytes} as object {@code name} of this step and adds it to {@link #storedOutputs}.
     * @throws IllegalArgumentException if {@code name} is not a plain file name; nothing is stored or recorded
     * @throws com.flowcortex.media.api.MediaStoreUnavailableException if the store does not take the write
     */
    MediaOutput store(MediaJobs media, String name, String mime, byte[] bytes) {
        MediaOutput stored = media.putRunObject(runId, stepOrder, name, mime, bytes);
        storedOutputs.add(stored);
        return stored;
    }
}
