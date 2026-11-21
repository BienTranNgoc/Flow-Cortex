package com.flowcortex.workflow.api;

import java.util.UUID;

/** What a member can read about a run after the fact: its step log and the waveforms of its sounds. */
public interface RunDiagnostics {

    /**
     * The step log of the caller's run, errors first, then warnings, then the rest by step order, cut to
     * {@code limit} after ordering. {@code level} is {@code error}, {@code warn}, {@code info} or {@code all}.
     * @throws com.flowcortex.shared.web.NotFoundException if the run is not the caller's or was deleted
     */
    RunLogView log(UUID currentUserId, UUID runId, String level, int limit);

    /**
     * The waveform of output {@code name} of step {@code stepOrder}, computed once per step attempt.
     * @throws com.flowcortex.shared.web.NotFoundException if the run is not the caller's, was deleted, or the
     *         step or output does not exist or is not downloadable
     * @throws org.springframework.web.server.ResponseStatusException 422 if the output is not audio; 503 if
     *         the decoders are busy or the store cannot serve the sound
     */
    PeaksView peaks(UUID currentUserId, UUID runId, int stepOrder, String name);
}
