package com.flowcortex.llm.gateway;

import java.util.Locale;

/**
 * The output-token ceiling of a JSON request. A cap below the ceiling truncates a structured answer
 * into an unusable one while costing the same, so callers cannot ask for one.
 */
public final class OutputTokenPolicy {

    private static final int GEMINI_CEILING = 65536;
    private static final int DEFAULT_CEILING = 16384;

    private OutputTokenPolicy() {
    }

    /** 65536 for a model id starting {@code gemini} (any case), 16384 for any other. */
    public static int jsonCap(String model) {
        return model != null && model.toLowerCase(Locale.ROOT).startsWith("gemini") ? GEMINI_CEILING : DEFAULT_CEILING;
    }
}
