package com.flowcortex.workflow.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.llm.api.LlmSlots;
import com.flowcortex.workflow.api.RunOptions;
import com.flowcortex.workflow.application.provider.DubProperties;
import com.flowcortex.workflow.application.provider.DubSlots;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/** Settings and step contexts shared by the dub step tests; the values are the production defaults. */
final class DubFixtures {

    private DubFixtures() {
    }

    static DubProperties dub(boolean enabled, double charsPerSecond) {
        return new DubProperties(enabled, charsPerSecond, 1.2, Map.of("female", "Kore", "male", "Charon"),
                new DubProperties.Pool(4, 4));
    }

    static DubProperties dub() {
        return dub(true, 13);
    }

    /** A slot port holding the shipped slot numbers: 440 s for a JSON call, 280 s for a voice call. */
    static LlmSlots slots(boolean configured) {
        LlmSlots slots = mock(LlmSlots.class);
        when(slots.configured()).thenReturn(configured);
        when(slots.jsonWorstCase(DubSlots.TRANSLATE)).thenReturn(Duration.ofSeconds(440));
        when(slots.speechWorstCase(DubSlots.SPEECH)).thenReturn(Duration.ofSeconds(280));
        return slots;
    }

    static LlmSlots slots() {
        return slots(true);
    }

    static StepContext context(String type, int order, String input, String voice) {
        return new StepContext(UUID.randomUUID(), UUID.randomUUID(), order, type, (short) 1, input, null, null,
                new RunOptions(voice), new ArrayList<>());
    }
}
