package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.runhistory.TopWorkflowView;
import com.flowcortex.workflow.api.runhistory.UsageDay;
import com.flowcortex.workflow.api.runhistory.UsageSummaryView;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.infrastructure.RunUsageRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a member's usage summary from the runs of the window and what each was charged. A run and its
 * net charge count on the local day the run started and on its workflow.
 */
final class UsageSummaries {

    static final int MAX_DAYS = 365;
    static final int MAX_TOP = 50;

    private UsageSummaries() {
    }

    static int clampDays(int days) {
        return Math.max(1, Math.min(MAX_DAYS, days));
    }

    static int clampTop(int top) {
        return Math.max(1, Math.min(MAX_TOP, top));
    }

    /**
     * @param charges net credits per run id; {@code null} while credits are off, which nulls every credit
     *        figure, and a run missing from the map was charged nothing
     * @param balance the member's balance, {@code null} while credits are off
     */
    static UsageSummaryView summarize(List<RunUsageRow> rows, Map<UUID, Long> charges, Long balance, LocalDate today,
            int days, int top) {
        boolean credits = charges != null;
        LocalDate from = today.minusDays(days - 1L);
        Map<LocalDate, long[]> perDay = new HashMap<>();
        Map<UUID, WorkflowTally> perWorkflow = new HashMap<>();
        long runs = 0;
        long failed = 0;
        long spent = 0;
        for (RunUsageRow row : rows) {
            LocalDate day = DailySeries.dayOf(row.startedAt());
            if (day.isBefore(from) || day.isAfter(today)) {
                continue;
            }
            long charge = credits ? charges.getOrDefault(row.runId(), 0L) : 0L;
            boolean error = row.status() == RunStatus.ERROR;
            long[] bucket = perDay.computeIfAbsent(day, d -> new long[3]);
            bucket[0]++;
            bucket[1] += error ? 1 : 0;
            bucket[2] += charge;
            WorkflowTally tally = perWorkflow.computeIfAbsent(row.workflowId(),
                    id -> new WorkflowTally(id, row.workflowName()));
            tally.runs++;
            tally.credits += charge;
            runs++;
            failed += error ? 1 : 0;
            spent += charge;
        }
        List<UsageDay> series = new ArrayList<>(days);
        for (LocalDate day : DailySeries.days(today, days)) {
            long[] bucket = perDay.getOrDefault(day, new long[3]);
            series.add(new UsageDay(day, bucket[0], bucket[1], credits ? bucket[2] : null));
        }
        List<TopWorkflowView> topWorkflows = perWorkflow.values().stream()
                .sorted(Comparator.comparingLong((WorkflowTally t) -> t.credits).reversed()
                        .thenComparing(Comparator.comparingLong((WorkflowTally t) -> t.runs).reversed())
                        .thenComparing(t -> t.name))
                .limit(top)
                .map(t -> new TopWorkflowView(t.id, t.name, t.runs, credits ? t.credits : null,
                        credits ? oneDecimal((double) t.credits / t.runs) : null))
                .toList();
        double failureRate = runs == 0 ? 0.0 : Math.round(failed * 1000.0 / runs) / 10.0;
        return new UsageSummaryView(days, from, today, runs, failed, failureRate, credits ? spent : null, balance,
                series, topWorkflows);
    }

    private static double oneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static final class WorkflowTally {
        final UUID id;
        final String name;
        long runs;
        long credits;

        WorkflowTally(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
