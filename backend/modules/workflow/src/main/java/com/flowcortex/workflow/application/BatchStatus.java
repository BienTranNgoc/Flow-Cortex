package com.flowcortex.workflow.application;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The batch status model: which status a transition may leave and how a row follows its run. The sets here are the
 * ones the repository {@code WHERE} clauses name, so a transition and its write cannot drift apart.
 */
enum BatchStatus {
    RUNNING("running"),
    PAUSED("paused"),
    COMPLETED("completed"),
    COMPLETED_WITH_ERRORS("completed_with_errors"),
    CANCELLED("cancelled");

    static final String PAUSE_MEMBER = "member";
    static final String PAUSE_INSUFFICIENT_CREDITS = "insufficientCredits";
    static final String PAUSE_UNAVAILABLE = "unavailable";

    static final String CANCEL_MEMBER = "member";
    static final String CANCEL_WORKFLOW_UNAVAILABLE = "workflowUnavailable";
    static final String CANCEL_OWNER_INACTIVE = "ownerInactive";

    static final Set<BatchStatus> PAUSE_FROM = Set.copyOf(EnumSet.of(RUNNING));
    static final Set<BatchStatus> RESUME_FROM = Set.copyOf(EnumSet.of(PAUSED));
    static final Set<BatchStatus> CANCEL_FROM = Set.copyOf(EnumSet.of(RUNNING, PAUSED));
    static final Set<BatchStatus> FINISH_FROM = Set.copyOf(EnumSet.of(RUNNING, PAUSED));

    private final String value;

    BatchStatus(String value) {
        this.value = value;
    }

    /** The stored and API name. */
    String value() {
        return value;
    }

    static BatchStatus of(String stored) {
        return parse(stored).orElseThrow(() -> new IllegalArgumentException("Unknown batch status: " + stored));
    }

    /** Reads a stored or filter name; empty for anything that is not exactly one of them. */
    static Optional<BatchStatus> parse(String name) {
        return Arrays.stream(values()).filter(status -> status.value.equals(name)).findFirst();
    }

    boolean canPause() {
        return PAUSE_FROM.contains(this);
    }

    boolean canResume() {
        return RESUME_FROM.contains(this);
    }

    boolean canCancel() {
        return CANCEL_FROM.contains(this);
    }

    /**
     * The status this batch holds once no row is open: a running or paused one becomes completed (completed with
     * errors when a row failed), a cancelled one stays cancelled and only gets its finish time.
     */
    BatchStatus finished(int failedRows) {
        return switch (this) {
            case RUNNING, PAUSED -> failedRows > 0 ? COMPLETED_WITH_ERRORS : COMPLETED;
            case CANCELLED -> CANCELLED;
            case COMPLETED, COMPLETED_WITH_ERRORS -> throw new IllegalStateException("Batch already finished: " + value);
        };
    }

    /** A row of a batch. {@link #STARTING} is internal: a claimed row whose run is not recorded yet. */
    enum Row {
        PENDING("pending"),
        STARTING("starting"),
        RUNNING("running"),
        COMPLETED("completed"),
        FAILED("failed"),
        CANCELLED("cancelled");

        /** Rows that hold a start slot: they count against the batch, member and cluster caps. */
        static final Set<Row> LIVE = Set.copyOf(EnumSet.of(STARTING, RUNNING));
        /** Rows whose presence keeps a batch from finishing. */
        static final Set<Row> OPEN = Set.copyOf(EnumSet.of(PENDING, STARTING, RUNNING));
        /** A batch cancel moves these and nothing else: a starting row may have a run committing. */
        static final Set<Row> CANCEL_FROM = Set.copyOf(EnumSet.of(PENDING));

        private final String value;

        Row(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        static Row of(String stored) {
            return Arrays.stream(values()).filter(row -> row.value.equals(stored)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown batch row status: " + stored));
        }

        /** Reads a status filter: the names the API reports, so {@code starting} is not one of them. */
        static Optional<Row> parseReported(String name) {
            return Arrays.stream(values()).filter(row -> row != STARTING && row.value.equals(name)).findFirst();
        }

        /** What the API shows: a starting row has no run yet, so it is pending. */
        Row reported() {
            return this == STARTING ? PENDING : this;
        }

        /** The stored statuses a filter on a reported status has to match. */
        static Set<Row> storedFor(Row reported) {
            return reported == PENDING ? Set.copyOf(EnumSet.of(PENDING, STARTING)) : Set.of(reported);
        }

        /** The row status for a run status: a live run is running, a finished one maps to its final status. */
        static Row followingRun(String runStatus) {
            return switch (runStatus) {
                case "running", "waiting_approval", "cancelling" -> RUNNING;
                case "completed" -> COMPLETED;
                case "error" -> FAILED;
                case "cancelled" -> CANCELLED;
                default -> throw new IllegalArgumentException("Unknown run status: " + runStatus);
            };
        }

        /** Where a claimed row goes back to: pending, unless its batch was cancelled or finished meanwhile. */
        static Row backToPending(BatchStatus batch) {
            return batch == BatchStatus.RUNNING || batch == BatchStatus.PAUSED ? PENDING : CANCELLED;
        }

        Row afterBatchCancel() {
            return CANCEL_FROM.contains(this) ? CANCELLED : this;
        }
    }
}
