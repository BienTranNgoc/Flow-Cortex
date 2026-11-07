package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.RunOptions;
import com.flowcortex.workflow.application.provider.DubProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Settings and step contexts shared by the dub step tests; the values are the production defaults. */
final class DubFixtures {

    static final String KEY = "fake-key-3c9e";

    private DubFixtures() {
    }

    static DubProperties dub(boolean enabled, String key, double charsPerSecond) {
        return new DubProperties(enabled, charsPerSecond, 1.2, Map.of("female", "Kore", "male", "Charon"),
                new DubProperties.Gemini(key, "https://gemini.test/v1beta",
                        List.of("gemini-3.5-flash-lite", "gemini-3.8-flash"),
                        List.of("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts"), 3,
                        List.of(Duration.ofSeconds(10), Duration.ofSeconds(30)), Duration.ofSeconds(60)));
    }

    static DubProperties dub() {
        return dub(true, KEY, 13);
    }

    static StepContext context(String type, int order, String input, String voice) {
        return new StepContext(UUID.randomUUID(), UUID.randomUUID(), order, type, (short) 1, input, null, null,
                new RunOptions(voice), new ArrayList<>());
    }
}
