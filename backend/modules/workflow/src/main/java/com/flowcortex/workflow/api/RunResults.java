package com.flowcortex.workflow.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read port over the runs whose results a member keeps. Every method selects runs through one
 * shared predicate, so a run outside it is invisible to all of them.
 */
public interface RunResults {

    /** The run with its steps, or empty when it does not exist or is not a result run. */
    Optional<RunResultView> completedRun(UUID runId);

    /**
     * The owner's most recently finished result runs, newest {@code finished_at} first.
     *
     * @throws IllegalArgumentException when {@code limit} is below 1
     */
    List<RunResultView> recentCompletedRuns(UUID ownerId, int limit);

    /**
     * Ids of result runs in ascending id order, strictly after {@code afterId} ({@code null} starts
     * from the beginning), at most {@code limit} of them.
     *
     * @throws IllegalArgumentException when {@code limit} is below 1
     */
    List<UUID> completedRunIds(UUID afterId, int limit);

    /** The stored text of step {@code stepOrder} of the owner's result run; empty for any other owner or run. */
    Optional<String> stepOutput(UUID ownerId, UUID runId, int stepOrder);
}
