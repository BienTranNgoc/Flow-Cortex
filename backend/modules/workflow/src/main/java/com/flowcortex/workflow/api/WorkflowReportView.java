package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Usage of one workflow: run counts for every status (lowercase wire value, zero when none) and the last
 * 30 local days of runs, oldest first.
 */
public record WorkflowReportView(
        AdminWorkflowRow workflow, Map<String, Long> countsByStatus, Instant lastRunAt, List<DayCount> series) {
}
