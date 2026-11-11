package com.flowcortex.workflow.api.runhistory;

import java.time.Instant;
import java.util.UUID;

/**
 * The filters of a run-history read; a {@code null} field matches every run. {@code status} is the lowercase
 * wire value, {@code from} is inclusive and {@code to} exclusive, both on the run's start.
 */
public record RunHistoryFilter(String status, UUID workflowId, Instant from, Instant to, boolean favoriteOnly) {

    public static RunHistoryFilter none() {
        return new RunHistoryFilter(null, null, null, null, false);
    }
}
