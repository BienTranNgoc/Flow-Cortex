package com.flowcortex.workflow.api.runhistory;

import java.time.LocalDate;
import java.util.List;

/**
 * A member's usage over the {@code days} days from {@code from} to {@code to} inclusive (local days).
 * {@code failureRate} is a percentage with one decimal; {@code creditsSpent} and {@code balance} are
 * {@code null} while credits are off.
 */
public record UsageSummaryView(
        int days, LocalDate from, LocalDate to, long runs, long failed, double failureRate, Long creditsSpent,
        Long balance, List<UsageDay> series, List<TopWorkflowView> topWorkflows) {
}
