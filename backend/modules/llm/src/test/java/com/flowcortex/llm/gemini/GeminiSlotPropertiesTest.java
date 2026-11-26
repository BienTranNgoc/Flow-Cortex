package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.flowcortex.llm.gemini.GeminiSlotProperties.Slot;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiSlotPropertiesTest {

    private static final List<Duration> WAITS = List.of(Duration.ofSeconds(10), Duration.ofSeconds(30));

    private static Slot slot(List<String> models, int attempts, List<Duration> waits, Duration timeout) {
        return Slot.of(models, attempts, waits, timeout);
    }

    private static Slot shipped() {
        return slot(List.of("a", "b"), 3, WAITS, Duration.ofSeconds(60));
    }

    @Test
    void theShippedSlotHasTheWorstCasesTheDubStepsBudgetFor() {
        assertThat(shipped().jsonWorstCase()).isEqualTo(Duration.ofSeconds(440));
        assertThat(shipped().speechWorstCase()).isEqualTo(Duration.ofSeconds(280));
    }

    @Test
    void aWrongNumberOfWaitsNamesTheSlotAndTheExpectedCount() {
        Slot oneWait = slot(List.of("a"), 3, List.of(Duration.ofSeconds(10)), Duration.ofSeconds(60));

        assertThatIllegalArgumentException().isThrownBy(() -> GeminiSlotProperties.of(Map.of("dub-translate", oneWait)))
                .withMessageContaining("dub-translate").withMessageContaining("2 waits");
    }

    @Test
    void noModelOrABlankModelFailsNamingTheSlot() {
        Slot none = slot(List.of(), 1, List.of(), Duration.ofSeconds(60));
        Slot blank = slot(List.of("a", " "), 1, List.of(), Duration.ofSeconds(60));

        assertThatIllegalArgumentException().isThrownBy(() -> GeminiSlotProperties.of(Map.of("one", none)))
                .withMessageContaining("one").withMessageContaining("model");
        assertThatIllegalArgumentException().isThrownBy(() -> GeminiSlotProperties.of(Map.of("two", blank)))
                .withMessageContaining("two").withMessageContaining("blank");
    }

    @Test
    void zeroAttemptsFailsNamingTheSlot() {
        Slot zero = slot(List.of("a"), 0, List.of(), Duration.ofSeconds(60));

        assertThatIllegalArgumentException().isThrownBy(() -> GeminiSlotProperties.of(Map.of("dub-speech", zero)))
                .withMessageContaining("dub-speech").withMessageContaining("attempts");
    }

    @Test
    void aMissingOrNonPositiveCallTimeoutFailsNamingTheSlot() {
        Slot zero = slot(List.of("a"), 1, List.of(), Duration.ZERO);
        Slot missing = slot(List.of("a"), 1, List.of(), null);

        assertThatIllegalArgumentException().isThrownBy(() -> GeminiSlotProperties.of(Map.of("z", zero)))
                .withMessageContaining("z").withMessageContaining("call-timeout");
        assertThatIllegalArgumentException().isThrownBy(() -> GeminiSlotProperties.of(Map.of("m", missing)))
                .withMessageContaining("m").withMessageContaining("call-timeout");
    }

    @Test
    void noSlotsAtAllIsValid() {
        assertThat(GeminiSlotProperties.of(null).slots()).isEmpty();
    }

    @Test
    void anUnknownSlotIsAProgrammingError() {
        GeminiSlotProperties properties = GeminiSlotProperties.of(Map.of("dub-translate", shipped()));

        assertThat(properties.slot("dub-translate")).isEqualTo(shipped());
        assertThatIllegalArgumentException().isThrownBy(() -> properties.slot("nope")).withMessageContaining("nope");
    }

    @Test
    void anUnknownThinkingValueFailsNamingTheSlot() {
        Slot low = new Slot(List.of("a"), 1, List.of(), Duration.ofSeconds(9), "low", null);

        assertThatIllegalArgumentException().isThrownBy(() -> GeminiSlotProperties.of(Map.of("dub-translate", low)))
                .withMessageContaining("flowcortex.llm.gemini.slots.dub-translate.thinking")
                .withMessageContaining("low");
    }

    @Test
    void anUnknownSlotThresholdFailsNamingItsKey() {
        Slot some = new Slot(List.of("a"), 1, List.of(), Duration.ofSeconds(9), null, "BLOCK_SOME");

        assertThatIllegalArgumentException().isThrownBy(() -> GeminiSlotProperties.of(Map.of("dub-translate", some)))
                .withMessageContaining("flowcortex.llm.gemini.slots.dub-translate.safety-threshold");
    }

    @Test
    void anUnknownGlobalThresholdFailsNamingItsKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GeminiSlotProperties(Map.of(), "BLOCK_SOME"))
                .withMessageContaining("flowcortex.llm.gemini.safety-threshold");
    }

    @Test
    void theGlobalThresholdDefaultsToBlockMediumAndAbove() {
        assertThat(GeminiSlotProperties.of(Map.of()).safetyThreshold()).isEqualTo("BLOCK_MEDIUM_AND_ABOVE");
        assertThat(new GeminiSlotProperties(Map.of(), "OFF").safetyThreshold()).isEqualTo("OFF");
    }

    @Test
    void aSlotThresholdOverridesTheGlobalOne() {
        Slot own = new Slot(List.of("a"), 1, List.of(), Duration.ofSeconds(9), null, "OFF");

        assertThat(own.thresholdOr("BLOCK_MEDIUM_AND_ABOVE")).isEqualTo("OFF");
        assertThat(shipped().thresholdOr("BLOCK_MEDIUM_AND_ABOVE")).isEqualTo("BLOCK_MEDIUM_AND_ABOVE");
    }

    @Test
    void thinkingDefaultsToDefault() {
        Slot off = new Slot(List.of("a"), 1, List.of(), Duration.ofSeconds(9), "off", null);
        Slot explicit = new Slot(List.of("a"), 1, List.of(), Duration.ofSeconds(9), "default", null);

        assertThat(shipped().thinkingMode()).isEqualTo(GeminiThinkingFloor.Mode.DEFAULT);
        assertThat(explicit.thinkingMode()).isEqualTo(GeminiThinkingFloor.Mode.DEFAULT);
        assertThat(off.thinkingMode()).isEqualTo(GeminiThinkingFloor.Mode.OFF);
    }
}
