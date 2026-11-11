package com.flowcortex.llm.registry;

import com.flowcortex.llm.api.LlmJsonResult;
import java.util.Locale;

/** Maps what a provider call came to onto the named outcome a test reports. */
public final class ProbeCodes {

    public static final String MISSING_CONFIG = "missing_config";
    public static final String BLOCKED = "blocked";

    private ProbeCodes() {
    }

    public static ProbeResult of(LlmJsonResult result, long latencyMs) {
        return switch (result) {
            case LlmJsonResult.Answered answered -> ProbeResult.answered(latencyMs, answered.outputTokens());
            case LlmJsonResult.Blocked blocked -> ProbeResult.failed(BLOCKED, latencyMs);
            case LlmJsonResult.Failed failed -> ProbeResult.failed(code(failed.kind()), latencyMs);
        };
    }

    /** The failure kind as a code: the lower-cased name, so {@code QUOTA_EXCEEDED} is {@code quota_exceeded}. */
    public static String code(LlmJsonResult.Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
