package com.flowcortex.workflow.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a fire's history row reports. A fire is written before its run starts and updated after, in separate
 * transactions, so the stored status can lag the truth; the run that holds the fire's request key is the
 * authority, and the stored status only answers when no run exists.
 */
final class ScheduleFireStatus {

    static final String STARTING = "starting";
    static final String STARTED = "started";
    static final String FAILED = "failed";

    static final String INTERRUPTED_CODE = "startInterrupted";
    static final String INTERRUPTED_KEY = "workflow.schedule.startInterrupted";

    /** Well past the start path's own wait for a racing winner, so a live start is never called interrupted. */
    static final Duration START_LIMIT = Duration.ofMinutes(2);

    private ScheduleFireStatus() {}

    /**
     * The reported fire: the message is a bundle key and arguments, resolved by the caller in the reader's
     * language. A run id and run status appear only for a run that is not deleted.
     */
    record Reported(String status, UUID runId, String runStatus, String errorCode, String messageKey,
            List<Object> messageArgs) {}

    /**
     * @param runId the run holding the fire's request key, or null when none does
     * @param runDeleted whether that run was deleted from the member's run history
     * @throws IllegalArgumentException for a stored status outside starting, started and failed
     */
    static Reported report(String storedStatus, String storedCode, String storedKey, List<Object> storedArgs,
            UUID runId, String runStatus, boolean runDeleted, Instant createdAt, Instant now) {
        if (runId != null) {
            return runDeleted ? started(null, null) : started(runId, runStatus);
        }
        return switch (storedStatus) {
            case FAILED -> new Reported(FAILED, null, null, storedCode, storedKey,
                    storedArgs == null ? List.of() : List.copyOf(storedArgs));
            case STARTED -> started(null, null);
            case STARTING -> Duration.between(createdAt, now).compareTo(START_LIMIT) < 0
                    ? new Reported(STARTING, null, null, null, null, List.of())
                    : new Reported(FAILED, null, null, INTERRUPTED_CODE, INTERRUPTED_KEY, List.of());
            default -> throw new IllegalArgumentException("Unknown stored fire status: " + storedStatus);
        };
    }

    private static Reported started(UUID runId, String runStatus) {
        return new Reported(STARTED, runId, runStatus, null, null, List.of());
    }
}
