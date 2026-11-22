package com.flowcortex.onboarding.domain;

import tools.jackson.databind.JsonNode;

/**
 * The open rule of the first-run journey. A stored progress of any version other than 2 counts as
 * empty. The progress write in {@code OnboardingRepository} applies the same predicate in SQL.
 */
public final class OnboardingOpen {

    /** The {@code entry} value for a member who should be taken through the journey. */
    public static final String ENTRY_FIRST = "first";

    private OnboardingOpen() {
    }

    /** True when the switch is on and the journey is unfinished or the member has no completed run. */
    public static boolean isOpen(JsonNode progress, boolean firstRunDone, boolean switchOn) {
        return switchOn && (isV2(progress) && !isFinished(progress) || !firstRunDone);
    }

    /** {@code "first"} when open and the journey is not a finished v2 one, else null. */
    public static String entry(JsonNode progress, boolean open) {
        return open && !(isV2(progress) && isFinished(progress)) ? ENTRY_FIRST : null;
    }

    private static boolean isV2(JsonNode progress) {
        return progress != null && progress.has("v") && progress.get("v").isIntegralNumber()
                && progress.get("v").intValue() == ProgressPatch.VERSION;
    }

    private static boolean isFinished(JsonNode progress) {
        JsonNode finishedAt = progress.get("finishedAt");
        return finishedAt != null && !finishedAt.isNull();
    }
}
