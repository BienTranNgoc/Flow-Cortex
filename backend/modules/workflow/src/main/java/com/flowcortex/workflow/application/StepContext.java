package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.workflow.api.RunOptions;
import java.util.List;
import java.util.UUID;

/**
 * What an executor gets for one step: its identity, the fencing {@code attempt}, the resolved
 * input, the step definition's {@code config} JSON ({@code null} when unset) and the options its
 * run was started with.
 *
 * <p>{@code jobInputs} are the objects a remote step declared and the API resolved for this run,
 * or {@code null} when it declares none and takes {@code input}, the run's upload reference.
 * {@code storedOutputs} are recorded as the step's outputs when it completes; an inline executor
 * fills it by storing each object through {@link #store}.
 */
record StepContext(UUID runId, UUID stepId, int stepOrder, String type, short attempt, String input, String config,
        RunOptions options, List<MediaJobInput> jobInputs, List<MediaOutput> storedOutputs) {

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
