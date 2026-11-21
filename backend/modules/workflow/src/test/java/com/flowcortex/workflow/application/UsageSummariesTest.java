package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.runhistory.TopWorkflowView;
import com.flowcortex.workflow.api.runhistory.UsageDay;
import com.flowcortex.workflow.api.runhistory.UsageSummaryView;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.infrastructure.RunUsageRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsageSummariesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);
    private static final UUID W = UUID.randomUUID();
    private static final UUID V = UUID.randomUUID();
    private static final Instant NOON = Instant.parse("2026-11-08T05:00:00Z");

    private static RunUsageRow run(UUID workflow, String name, RunStatus status, Instant startedAt) {
        return new RunUsageRow(UUID.randomUUID(), workflow, name, status, startedAt);
    }

    @Test
    void totalsFailureRateAndCreditsForTheThirtyDayExample() {
        List<RunUsageRow> rows = new ArrayList<>();
        rows.add(run(W, "W", RunStatus.COMPLETED, NOON.minusSeconds(86_400)));
        rows.add(run(W, "W", RunStatus.ERROR, NOON.minusSeconds(2 * 86_400)));
        rows.add(run(W, "W", RunStatus.COMPLETED, NOON.minusSeconds(3 * 86_400)));
        rows.add(run(W, "W", RunStatus.COMPLETED, NOON.minusSeconds(4 * 86_400)));
        rows.add(run(V, "V", RunStatus.COMPLETED, NOON));
        Map<UUID, Long> charges = Map.of(rows.get(0).runId(), 50L, rows.get(1).runId(), 10L,
                rows.get(2).runId(), 30L, rows.get(3).runId(), 30L, rows.get(4).runId(), 40L);

        UsageSummaryView summary = UsageSummaries.summarize(rows, charges, 900L, TODAY, 30, 10);

        assertThat(summary.runs()).isEqualTo(5);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failureRate()).isEqualTo(20.0);
        assertThat(summary.creditsSpent()).isEqualTo(160L);
        assertThat(summary.balance()).isEqualTo(900L);
        assertThat(summary.series()).hasSize(30);
        assertThat(summary.topWorkflows()).extracting(TopWorkflowView::name).containsExactly("W", "V");
        assertThat(summary.topWorkflows().get(0)).satisfies(top -> {
            assertThat(top.runs()).isEqualTo(4);
            assertThat(top.credits()).isEqualTo(120L);
            assertThat(top.averageCredits()).isEqualTo(30.0);
        });
    }

    @Test
    void failureRateIsZeroWithNoRuns() {
        UsageSummaryView summary = UsageSummaries.summarize(List.of(), Map.of(), 5L, TODAY, 7, 10);

        assertThat(summary.runs()).isZero();
        assertThat(summary.failureRate()).isZero();
        assertThat(summary.creditsSpent()).isZero();
        assertThat(summary.topWorkflows()).isEmpty();
        assertThat(summary.series()).hasSize(7).allSatisfy(day -> assertThat(day.runs()).isZero());
    }

    @Test
    void aRunAndItsChargeCountOnTheLocalStartDay() {
        RunUsageRow nearMidnight = run(W, "W", RunStatus.COMPLETED, Instant.parse("2026-11-07T17:30:00Z"));

        UsageSummaryView summary = UsageSummaries.summarize(List.of(nearMidnight),
                Map.of(nearMidnight.runId(), 12L), 0L, TODAY, 7, 10);

        assertThat(summary.series()).extracting(UsageDay::day).first().isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(summary.series().get(6)).isEqualTo(new UsageDay(TODAY, 1, 0, 12L));
        assertThat(summary.series().subList(0, 6)).allSatisfy(day -> {
            assertThat(day.runs()).isZero();
            assertThat(day.credits()).isZero();
        });
    }

    @Test
    void topWorkflowsOrderByCreditsThenRunsThenName() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<RunUsageRow> rows = List.of(run(a, "Zed", RunStatus.COMPLETED, NOON), run(b, "Beta", RunStatus.COMPLETED, NOON),
                run(b, "Beta", RunStatus.COMPLETED, NOON), run(c, "Alpha", RunStatus.COMPLETED, NOON),
                run(d, "Rich", RunStatus.COMPLETED, NOON));
        Map<UUID, Long> charges = Map.of(rows.get(0).runId(), 10L, rows.get(1).runId(), 5L, rows.get(2).runId(), 5L,
                rows.get(3).runId(), 10L, rows.get(4).runId(), 99L);

        UsageSummaryView summary = UsageSummaries.summarize(rows, charges, 0L, TODAY, 30, 10);

        assertThat(summary.topWorkflows()).extracting(TopWorkflowView::name)
                .containsExactly("Rich", "Beta", "Alpha", "Zed");
    }

    @Test
    void creditFiguresAreNullWhenCreditsAreOff() {
        RunUsageRow row = run(W, "W", RunStatus.ERROR, NOON);

        UsageSummaryView summary = UsageSummaries.summarize(List.of(row), null, null, TODAY, 7, 10);

        assertThat(summary.runs()).isEqualTo(1);
        assertThat(summary.creditsSpent()).isNull();
        assertThat(summary.balance()).isNull();
        assertThat(summary.series()).allSatisfy(day -> assertThat(day.credits()).isNull());
        assertThat(summary.topWorkflows()).singleElement().satisfies(top -> {
            assertThat(top.credits()).isNull();
            assertThat(top.averageCredits()).isNull();
            assertThat(top.runs()).isEqualTo(1);
        });
    }

    @Test
    void daysAndTopAreClamped() {
        assertThat(UsageSummaries.clampDays(0)).isEqualTo(1);
        assertThat(UsageSummaries.clampDays(400)).isEqualTo(365);
        assertThat(UsageSummaries.clampDays(30)).isEqualTo(30);
        assertThat(UsageSummaries.clampTop(0)).isEqualTo(1);
        assertThat(UsageSummaries.clampTop(500)).isEqualTo(50);
        assertThat(UsageSummaries.clampTop(10)).isEqualTo(10);
    }

    @Test
    void topIsCutToTheRequestedCount() {
        List<RunUsageRow> rows = List.of(run(W, "W", RunStatus.COMPLETED, NOON), run(V, "V", RunStatus.COMPLETED, NOON));

        UsageSummaryView summary = UsageSummaries.summarize(rows, Map.of(), 0L, TODAY, 7, 1);

        assertThat(summary.topWorkflows()).hasSize(1);
    }
}
