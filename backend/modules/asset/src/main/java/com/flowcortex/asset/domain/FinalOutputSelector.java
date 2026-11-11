package com.flowcortex.asset.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Picks the outputs a run leaves in the library: the highest step that produced at least one
 * video, image or audio object in the media bucket. Pure, so the rule is locked by its own test.
 */
public final class FinalOutputSelector {

    static final String UNSUPPORTED_KIND = "unsupported_kind";

    private FinalOutputSelector() {
    }

    /**
     * @param stepsDescending the outputs of each step's final attempt, highest step first
     */
    public static Selection select(List<StepOutputs> stepsDescending) {
        List<SkippedOutput> skipped = new ArrayList<>();
        for (StepOutputs step : stepsDescending) {
            List<OutputCandidate> accepted = new ArrayList<>();
            for (OutputCandidate output : step.outputs()) {
                if (!output.inMediaBucket()) {
                    continue;
                }
                String kind = classify(output.mimeType());
                if (kind == null) {
                    skipped.add(new SkippedOutput(output.mediaAssetId(), UNSUPPORTED_KIND));
                } else {
                    accepted.add(output.withKind(kind));
                }
            }
            if (!accepted.isEmpty()) {
                accepted.sort(Comparator.comparing(OutputCandidate::objectKey));
                return new Selection(List.copyOf(accepted), List.copyOf(skipped));
            }
        }
        return new Selection(List.of(), List.copyOf(skipped));
    }

    private static String classify(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String mime = mimeType.toLowerCase(Locale.ROOT);
        if (mime.startsWith("video/")) {
            return "video";
        }
        if (mime.startsWith("image/")) {
            return "image";
        }
        if (mime.startsWith("audio/")) {
            return "audio";
        }
        return null;
    }

    /** The outputs of one step's final attempt. */
    public record StepOutputs(UUID stepId, List<OutputCandidate> outputs) {
    }

    /** An output left out of the library, with its reason code. */
    public record SkippedOutput(UUID mediaAssetId, String reason) {
    }

    /** The final step's registrable outputs in object-key order, plus what was skipped on the way. */
    public record Selection(List<OutputCandidate> candidates, List<SkippedOutput> skipped) {
    }
}
