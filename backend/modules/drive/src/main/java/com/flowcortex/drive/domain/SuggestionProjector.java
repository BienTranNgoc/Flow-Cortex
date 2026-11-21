package com.flowcortex.drive.domain;

import com.flowcortex.drive.domain.DriveRoles.ContentType;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResultView.Output;
import com.flowcortex.workflow.api.RunResultView.Step;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Projects recent completed runs onto the candidates a member may keep as Drive items. */
public final class SuggestionProjector {

    public static final int INPUT_MIN = 40;
    public static final int PREVIEW_MAX = 160;

    /**
     * One candidate. A media candidate carries {@code stepOrder} and {@code output}; a text candidate carries
     * {@code textValue} (null with {@code tooLong} when over the item cap); an input candidate has no step.
     */
    public record Suggestion(String id, ContentType kind, String previewText, String textValue, boolean tooLong,
            String roleHint, String workflowName, String stepType, UUID runId, Instant at, Integer stepOrder,
            Output output) {
    }

    private SuggestionProjector() {
    }

    /** Candidates of the runs in the given order, first occurrence of an id kept, cut to {@code limit}. */
    public static List<Suggestion> project(List<RunResultView> runs, int limit) {
        List<Suggestion> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RunResultView run : runs) {
            for (Suggestion suggestion : candidatesOf(run)) {
                if (seen.add(suggestion.id())) {
                    all.add(suggestion);
                }
            }
        }
        return all.size() > limit ? List.copyOf(all.subList(0, limit)) : List.copyOf(all);
    }

    private static List<Suggestion> candidatesOf(RunResultView run) {
        List<Suggestion> out = new ArrayList<>();
        List<Step> latestFirst = run.steps().stream()
                .filter(ResultRules::isCompleted)
                .sorted(Comparator.comparingInt(Step::stepOrder).reversed())
                .toList();
        for (Step step : latestFirst) {
            Instant at = step.finishedAt() != null ? step.finishedAt() : run.finishedAt();
            ResultRules.textResult(step).ifPresent(text -> out.add(text(step.stepId().toString(), text, null,
                    run, step.type(), at, step.stepOrder())));
            for (Output output : ResultRules.mediaOutputs(step)) {
                ContentType kind = ResultRules.mediaKind(output.mime()).orElseThrow();
                out.add(new Suggestion(step.stepId() + "/" + output.name(), kind, preview(output.name()), null, false,
                        roleHint(kind), run.workflowName(), step.type(), run.runId(), at, step.stepOrder(), output));
            }
        }
        String input = run.textInput();
        if (input != null && input.strip().length() >= INPUT_MIN) {
            out.add(text(run.runId() + "/input", input, "audience_note", run, null, run.finishedAt(), null));
        }
        return out;
    }

    private static Suggestion text(String id, String text, String roleHint, RunResultView run, String stepType,
            Instant at, Integer stepOrder) {
        boolean tooLong = text.length() > DriveItemRules.TEXT_MAX;
        return new Suggestion(id, ContentType.TEXT, preview(text), tooLong ? null : text, tooLong, roleHint,
                run.workflowName(), stepType, run.runId(), at, stepOrder, null);
    }

    private static String roleHint(ContentType kind) {
        return switch (kind) {
            case IMAGE -> "product_photo";
            case VIDEO -> "driver_video";
            case AUDIO -> "audio_asset";
            default -> null;
        };
    }

    private static String preview(String text) {
        return ResultRules.cut(text.replaceAll("\\s+", " ").strip(), PREVIEW_MAX);
    }
}
