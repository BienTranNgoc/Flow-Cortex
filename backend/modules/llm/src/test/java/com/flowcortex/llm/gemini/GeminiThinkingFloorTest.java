package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.gemini.GeminiThinkingFloor.Mode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class GeminiThinkingFloorTest {

    private static ObjectNode off(String model) {
        return GeminiThinkingFloor.thinkingConfig(Mode.OFF, model).orElseThrow();
    }

    @Test
    void flashLiteThinksAtMinimal() {
        assertThat(off("gemini-3.5-flash-lite").path("thinkingLevel").asString()).isEqualTo("MINIMAL");
        assertThat(off("gemini-3.5-flash-lite").size()).isEqualTo(1);
    }

    @Test
    void threePointSevenAndThreePointEightFlashThinkAtLow() {
        assertThat(off("gemini-3.7-flash").path("thinkingLevel").asString()).isEqualTo("LOW");
        assertThat(off("gemini-3.8-flash").path("thinkingLevel").asString()).isEqualTo("LOW");
    }

    @Test
    void twoPointFiveProKeepsAHundredTwentyEightTokens() {
        ObjectNode config = off("gemini-2.5-pro-preview");

        assertThat(config.path("thinkingBudget").asInt()).isEqualTo(128);
        assertThat(config.has("thinkingLevel")).isFalse();
    }

    @Test
    void twoPointFiveFlashThinksNotAtAll() {
        ObjectNode config = off("gemini-2.5-flash-lite");

        assertThat(config.has("thinkingBudget")).isTrue();
        assertThat(config.path("thinkingBudget").asInt()).isZero();
        assertThat(config.has("thinkingLevel")).isFalse();
    }

    @Test
    void anUnknownModelGetsNoSetting() {
        assertThat(GeminiThinkingFloor.thinkingConfig(Mode.OFF, "gemini-9-experimental")).isEmpty();
        assertThat(GeminiThinkingFloor.thinkingConfig(Mode.OFF, null)).isEmpty();
    }

    @Test
    void aDefaultSlotSendsNothing() {
        assertThat(GeminiThinkingFloor.thinkingConfig(Mode.DEFAULT, "gemini-3.8-flash")).isEmpty();
        assertThat(GeminiThinkingFloor.thinkingConfig(Mode.DEFAULT, "gemini-2.5-flash")).isEmpty();
    }
}
