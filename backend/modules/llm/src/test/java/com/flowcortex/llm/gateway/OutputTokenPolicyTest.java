package com.flowcortex.llm.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutputTokenPolicyTest {

    @Test
    void aGeminiModelGetsSixtyFiveThousandFiveHundredThirtySix() {
        assertThat(OutputTokenPolicy.jsonCap("gemini-3.5-flash-lite")).isEqualTo(65536);
        assertThat(OutputTokenPolicy.jsonCap("GEMINI-3.8-FLASH")).isEqualTo(65536);
    }

    @Test
    void anyOtherModelGetsSixteenThousandThreeHundredEightyFour() {
        assertThat(OutputTokenPolicy.jsonCap("other-model")).isEqualTo(16384);
    }
}
