package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.RunLogEntry;
import com.flowcortex.workflow.api.RunLogSummary;
import com.flowcortex.workflow.api.RunLogView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Projects a run's steps into log lines; Nodi stores no per-step log, so levels come from the step's own state. */
final class RunLogs {

    static final String ERROR = "error";
    static final String WARN = "warn";
    static final String INFO = "info";
    /** The values {@code level} may take. */
    static final Set<String> LEVELS = Set.of(ERROR, WARN, INFO, "all");

    private static final Comparator<RunLogEntry> ERRORS_FIRST = Comparator
            .comparingInt((RunLogEntry entry) -> rank(entry.level()))
            .thenComparingInt(RunLogEntry::stepOrder);

    private RunLogs() {
    }

    /**
     * The log of a run with {@code steps}: entries at or above {@code level} ordered error, warn, info and then by
     * step order, cut to {@code limit} after ordering. The summary and step count cover every step.
     */
    static RunLogView project(String runStatus, String traceId, List<RunStep> steps, String level, int limit) {
        List<RunLogEntry> all = steps.stream().map(RunLogs::entry).sorted(ERRORS_FIRST).toList();
        int errors = (int) all.stream().filter(entry -> ERROR.equals(entry.level())).count();
        int warnings = (int) all.stream().filter(entry -> WARN.equals(entry.level())).count();
        String firstError = all.stream().filter(entry -> ERROR.equals(entry.level()))
                .min(Comparator.comparingInt(RunLogEntry::stepOrder)).map(RunLogEntry::message).orElse(null);
        int keepRank = rank(level);
        List<RunLogEntry> shown = all.stream().filter(entry -> rank(entry.level()) <= keepRank).limit(limit).toList();
        return new RunLogView(runStatus, traceId, steps.size(), shown, new RunLogSummary(errors, warnings, firstError));
    }

    private static RunLogEntry entry(RunStep step) {
        String level = levelOf(step);
        Long durationMs = step.getStartedAt() == null || step.getFinishedAt() == null ? null
                : Duration.between(step.getStartedAt(), step.getFinishedAt()).toMillis();
        return new RunLogEntry(step.getStepOrder(), step.getType(), level,
                step.getStatus().name().toLowerCase(Locale.ROOT), ERROR.equals(level) ? step.getErrorMessage() : null,
                step.getErrorCode(), step.getAttempt(), step.getDeliveryCount(), step.getStartedAt(),
                step.getFinishedAt(), durationMs);
    }

    private static String levelOf(RunStep step) {
        if (step.getStatus() == RunStepStatus.ERROR) {
            return ERROR;
        }
        if (step.getStatus() == RunStepStatus.COMPLETED && (step.getAttempt() > 1 || step.getDeliveryCount() > 1)) {
            return WARN;
        }
        return INFO;
    }

    private static int rank(String level) {
        return switch (level) {
            case ERROR -> 0;
            case WARN -> 1;
            default -> 2;
        };
    }
}
