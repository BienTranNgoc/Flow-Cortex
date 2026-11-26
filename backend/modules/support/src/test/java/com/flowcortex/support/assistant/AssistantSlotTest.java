package com.flowcortex.support.assistant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AssistantSlotTest {

    @Test
    void aWorstCaseWithinTheAnswerTimeoutPasses() {
        assertThatCode(() -> AssistantSlot.check(Duration.ofSeconds(18), Duration.ofSeconds(20)))
                .doesNotThrowAnyException();
    }

    @Test
    void aWorstCaseEqualToTheAnswerTimeoutPasses() {
        assertThatCode(() -> AssistantSlot.check(Duration.ofSeconds(20), Duration.ofSeconds(20)))
                .doesNotThrowAnyException();
    }

    @Test
    void aWorstCaseOverTheAnswerTimeoutFailsNamingTheSlotAndBothDurations() {
        assertThatThrownBy(() -> AssistantSlot.check(Duration.ofSeconds(30), Duration.ofSeconds(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("support-assistant")
                .hasMessageContaining("PT30S")
                .hasMessageContaining("PT20S");
    }
}
