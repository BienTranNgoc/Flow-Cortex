package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.RunLogEntry;
import com.flowcortex.workflow.api.RunLogView;
import com.flowcortex.workflow.domain.RunStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RunLogsTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    private static RunStep pending(int order) {
        return new RunStep(UUID.randomUUID(), RUN, (short) order, "echo");
    }

    private static RunStep completed(int order, int attempt, int deliveries) {
        RunStep step = pending(order);
        step.markRunning(T0);
        if (deliveries > 0) {
            step.recordPickup(T0, T0.plusSeconds(60), T0.plusSeconds(600), (short) deliveries);
        }
        step.markCompleted("in", "out", T0.plusSeconds(3));
        ReflectionTestUtils.setField(step, "attempt", (short) attempt);
        return step;
    }

    private static RunStep errored(int order, String code, String message) {
        RunStep step = pending(order);
        step.markRunning(T0);
        step.markError("in", code, message, T0.plusSeconds(1));
        return step;
    }

    private static RunLogView log(List<RunStep> steps, String level, int limit) {
        return RunLogs.project("error", "4bf92f3577b34da6a3ce929d0e0e4736", steps, level, limit);
    }

    private static List<Integer> orders(RunLogView view) {
        return view.entries().stream().map(RunLogEntry::stepOrder).toList();
    }

    @Test
    void anErroredStepIsAnErrorWithItsMessage() {
        RunLogView view = log(List.of(errored(1, "media.timeout", "Worker timed out")), "all", 50);

        RunLogEntry entry = view.entries().getFirst();
        assertThat(entry.level()).isEqualTo("error");
        assertThat(entry.status()).isEqualTo("error");
        assertThat(entry.message()).isEqualTo("Worker timed out");
        assertThat(entry.errorCode()).isEqualTo("media.timeout");
    }

    @Test
    void aRedeliveredOrRetriedFinishedStepIsAWarning() {
        RunLogView view = log(List.of(completed(1, 1, 2), completed(2, 2, 0), completed(3, 1, 1)), "all", 50);

        assertThat(view.entries()).extracting(RunLogEntry::stepOrder, RunLogEntry::level)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "warn"),
                        org.assertj.core.groups.Tuple.tuple(2, "warn"),
                        org.assertj.core.groups.Tuple.tuple(3, "info"));
    }

    @Test
    void otherStepsAreInfoWithoutMessage() {
        RunStep running = pending(2);
        running.markRunning(T0);

        RunLogView view = log(List.of(completed(1, 1, 1), running, pending(3)), "all", 50);

        assertThat(view.entries()).extracting(RunLogEntry::level).containsOnly("info");
        assertThat(view.entries()).extracting(RunLogEntry::message).containsOnlyNulls();
        assertThat(view.entries()).extracting(RunLogEntry::status).containsExactly("completed", "running", "pending");
    }

    @Test
    void levelFiltersKeepTheirLevelsAndAbove() {
        List<RunStep> steps = List.of(completed(1, 1, 2), completed(2, 1, 1), errored(3, null, "boom"), pending(4));

        assertThat(orders(log(steps, "error", 50))).containsExactly(3);
        assertThat(orders(log(steps, "warn", 50))).containsExactly(3, 1);
        assertThat(orders(log(steps, "info", 50))).containsExactly(3, 1, 2, 4);
        assertThat(orders(log(steps, "all", 50))).containsExactly(3, 1, 2, 4);
    }

    @Test
    void ordersErrorWarnInfoThenStepOrderAndLimitsAfterOrdering() {
        List<RunStep> steps = List.of(completed(1, 1, 2), completed(2, 1, 1), errored(3, null, "boom"));

        assertThat(orders(log(steps, "warn", 1))).containsExactly(3);
        RunLogView full = log(steps, "warn", 50);
        assertThat(orders(full)).containsExactly(3, 1);
        assertThat(full.summary().warningCount()).isEqualTo(1);
        assertThat(orders(log(steps, "all", 2))).containsExactly(3, 1);
    }

    @Test
    void summaryCountsAndFirstError() {
        List<RunStep> steps = List.of(completed(1, 1, 2), errored(2, null, "first"), pending(3));

        RunLogView limited = log(steps, "info", 1);

        assertThat(limited.entries()).hasSize(1);
        assertThat(limited.stepCount()).isEqualTo(3);
        assertThat(limited.summary().errorCount()).isEqualTo(1);
        assertThat(limited.summary().warningCount()).isEqualTo(1);
        assertThat(limited.summary().firstError()).isEqualTo("first");
        assertThat(limited.status()).isEqualTo("error");
        assertThat(limited.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void aRunWithoutErrorsHasNoFirstError() {
        RunLogView view = log(List.of(completed(1, 1, 1)), "all", 50);

        assertThat(view.summary().errorCount()).isZero();
        assertThat(view.summary().firstError()).isNull();
    }

    @Test
    void durationNeedsBothTimes() {
        RunStep running = pending(2);
        running.markRunning(T0);

        RunLogView view = log(List.of(completed(1, 1, 1), running, pending(3)), "all", 50);

        assertThat(view.entries()).extracting(RunLogEntry::durationMs).containsExactly(3000L, null, null);
    }
}
