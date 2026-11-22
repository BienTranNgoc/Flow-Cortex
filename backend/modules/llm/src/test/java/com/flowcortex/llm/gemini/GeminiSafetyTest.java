package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;

class GeminiSafetyTest {

    private static final String KEY = "flowcortex.llm.gemini.safety-threshold";

    @Test
    void theFourCategoriesAtTheGivenThreshold() {
        ArrayNode settings = GeminiSafety.settings("BLOCK_ONLY_HIGH");

        assertThat(settings).hasSize(4);
        assertThat(settings.get(0).path("category").asString()).isEqualTo("HARM_CATEGORY_HARASSMENT");
        assertThat(settings.get(1).path("category").asString()).isEqualTo("HARM_CATEGORY_HATE_SPEECH");
        assertThat(settings.get(2).path("category").asString()).isEqualTo("HARM_CATEGORY_SEXUALLY_EXPLICIT");
        assertThat(settings.get(3).path("category").asString()).isEqualTo("HARM_CATEGORY_DANGEROUS_CONTENT");
        settings.forEach(entry -> assertThat(entry.path("threshold").asString()).isEqualTo("BLOCK_ONLY_HIGH"));
    }

    @Test
    void everyGeminiThresholdIsAccepted() {
        assertThat(GeminiSafety.THRESHOLDS).containsExactlyInAnyOrder("BLOCK_LOW_AND_ABOVE",
                "BLOCK_MEDIUM_AND_ABOVE", "BLOCK_ONLY_HIGH", "BLOCK_NONE", "OFF");
        for (String threshold : GeminiSafety.THRESHOLDS) {
            assertThatCode(() -> GeminiSafety.check(KEY, threshold)).doesNotThrowAnyException();
        }
    }

    @Test
    void anUnknownThresholdIsRefusedNamingTheKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> GeminiSafety.check(KEY, "BLOCK_SOME"))
                .withMessageContaining(KEY).withMessageContaining("BLOCK_SOME");
    }

    @Test
    void theUnspecifiedThresholdIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GeminiSafety.check(KEY, "HARM_BLOCK_THRESHOLD_UNSPECIFIED"))
                .withMessageContaining(KEY);
    }

    @Test
    void aBlankThresholdIsRefusedNamingTheKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> GeminiSafety.check(KEY, " "))
                .withMessageContaining(KEY);
    }
}
