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
        return new Slot(models, attempts, waits, timeout);
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

        assertThatIllegalArgumentException().isThrownBy(() -> new GeminiSlotProperties(Map.of("dub-translate", oneWait)))
                .withMessageContaining("dub-translate").withMessageContaining("2 waits");
    }

    @Test
    void noModelOrABlankModelFailsNamingTheSlot() {
        Slot none = slot(List.of(), 1, List.of(), Duration.ofSeconds(60));
        Slot blank = slot(List.of("a", " "), 1, List.of(), Duration.ofSeconds(60));

        assertThatIllegalArgumentException().isThrownBy(() -> new GeminiSlotProperties(Map.of("one", none)))
                .withMessageContaining("one").withMessageContaining("model");
        assertThatIllegalArgumentException().isThrownBy(() -> new GeminiSlotProperties(Map.of("two", blank)))
                .withMessageContaining("two").withMessageContaining("blank");
    }

    @Test
    void zeroAttemptsFailsNamingTheSlot() {
        Slot zero = slot(List.of("a"), 0, List.of(), Duration.ofSeconds(60));

        assertThatIllegalArgumentException().isThrownBy(() -> new GeminiSlotProperties(Map.of("dub-speech", zero)))
                .withMessageContaining("dub-speech").withMessageContaining("attempts");
    }

    @Test
    void aMissingOrNonPositiveCallTimeoutFailsNamingTheSlot() {
        Slot zero = slot(List.of("a"), 1, List.of(), Duration.ZERO);
        Slot missing = slot(List.of("a"), 1, List.of(), null);

        assertThatIllegalArgumentException().isThrownBy(() -> new GeminiSlotProperties(Map.of("z", zero)))
                .withMessageContaining("z").withMessageContaining("call-timeout");
        assertThatIllegalArgumentException().isThrownBy(() -> new GeminiSlotProperties(Map.of("m", missing)))
                .withMessageContaining("m").withMessageContaining("call-timeout");
    }

    @Test
    void noSlotsAtAllIsValid() {
        assertThat(new GeminiSlotProperties(null).slots()).isEmpty();
    }

    @Test
    void anUnknownSlotIsAProgrammingError() {
        GeminiSlotProperties properties = new GeminiSlotProperties(Map.of("dub-translate", shipped()));

        assertThat(properties.slot("dub-translate")).isEqualTo(shipped());
        assertThatIllegalArgumentException().isThrownBy(() -> properties.slot("nope")).withMessageContaining("nope");
    }
}
