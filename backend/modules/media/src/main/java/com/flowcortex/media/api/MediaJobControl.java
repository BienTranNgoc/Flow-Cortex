package com.flowcortex.media.api;

import java.util.UUID;

/**
 * Port implemented by the workflow engine so the media module can answer worker control checks
 * without depending on {@code workflow}.
 */
public interface MediaJobControl {

    /**
     * Evaluates the current state of step {@code jobId} on run {@code runId} at {@code attempt}.
     *
     * <p>Returns {@link MediaControlResponse.Decision#DISCARD_STALE} when the run or step does not
     * exist, the attempt no longer matches, or the step is not currently {@code running};
     * {@link MediaControlResponse.Decision#CANCEL_SIBLING_FAILED} when the step is still
     * {@code running} at {@code attempt} while its sibling clip or run has terminally failed;
     * otherwise {@link MediaControlResponse.Decision#CONTINUE}.
     */
    MediaControlResponse.Decision decide(UUID runId, UUID jobId, int attempt);
}
