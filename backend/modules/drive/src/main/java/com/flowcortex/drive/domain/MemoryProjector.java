package com.flowcortex.drive.domain;

import com.flowcortex.drive.domain.DriveRoles.ContentType;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResultView.Output;
import com.flowcortex.workflow.api.RunResultView.Step;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Projects a completed run onto the Memory entries it deserves: the results of its last productive step. */
public final class MemoryProjector {

    public static final int TITLE_MAX = 200;
    public static final int PREVIEW_MAX = 500;

    /** One Memory row before it is stored; {@code resultKey} is stable across re-projections of the run. */
    public record Entry(UUID stepId, int stepOrder, String resultKey, ContentType kind, String title,
            String previewText, String bucket, String objectKey, String fileName, String mimeType, long sizeBytes,
            Instant resultAt) {
    }

    private MemoryProjector() {
    }

    public static List<Entry> project(RunResultView run) {
        String title = ResultRules.cut(run.workflowName() == null ? "" : run.workflowName(), TITLE_MAX);
        List<Step> finalFirst = run.steps().stream()
                .filter(ResultRules::isCompleted)
                .sorted(Comparator.comparingInt(Step::stepOrder).reversed())
                .toList();
        for (Step step : finalFirst) {
            List<Entry> entries = entriesOf(run, step, title);
            if (!entries.isEmpty()) {
                return entries;
            }
        }
        return List.of();
    }

    private static List<Entry> entriesOf(RunResultView run, Step step, String title) {
        Instant at = step.finishedAt() != null ? step.finishedAt() : run.finishedAt();
        List<Entry> entries = new ArrayList<>();
        ResultRules.textResult(step).ifPresent(text -> entries.add(new Entry(step.stepId(), step.stepOrder(),
                step.stepId().toString(), ContentType.TEXT, title, ResultRules.cut(text, PREVIEW_MAX), null, null,
                null, null, text.getBytes(StandardCharsets.UTF_8).length, at)));
        for (Output output : ResultRules.mediaOutputs(step)) {
            entries.add(new Entry(step.stepId(), step.stepOrder(), step.stepId() + "/" + output.name(),
                    ResultRules.mediaKind(output.mime()).orElseThrow(), title, null, output.bucket(),
                    output.objectKey(), output.name(), output.mime(), output.bytes(), at));
        }
        return List.copyOf(entries);
    }
}
