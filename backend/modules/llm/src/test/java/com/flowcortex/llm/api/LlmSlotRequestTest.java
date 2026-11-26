package com.flowcortex.llm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class LlmSlotRequestTest {

    @Test
    void aBlankSlotOrBlankUserTextIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmSlotRequest(" ", null, "q", null, null))
                .withMessageContaining("slot");
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmSlotRequest("s", null, "", null, null))
                .withMessageContaining("userText");
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmSlotRequest("s", null, null, null, null));
    }

    @Test
    void aTemperatureOutsideZeroToTwoIsRefusedAndTheBoundsAreAccepted() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmSlotRequest("s", null, "q", null, 2.5))
                .withMessageContaining("temperature");
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmSlotRequest("s", null, "q", null, -0.1));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmSlotRequest("s", null, "q", null, Double.NaN));
        assertThat(new LlmSlotRequest("s", null, "q", null, 0.0).temperature()).isEqualTo(0.0);
        assertThat(new LlmSlotRequest("s", null, "q", null, 2.0).temperature()).isEqualTo(2.0);
        assertThat(new LlmSlotRequest("s", null, "q", null, null).temperature()).isNull();
    }
}
