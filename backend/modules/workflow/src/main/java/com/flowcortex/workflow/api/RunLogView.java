package com.flowcortex.workflow.api;

import java.util.List;

/**
 * A run's step log. {@code traceId} is {@code null} for a run started before trace ids were stored;
 * {@code stepCount} counts every step, not only the returned {@code entries}.
 */
public record RunLogView(String status, String traceId, int stepCount, List<RunLogEntry> entries,
        RunLogSummary summary) {
}
