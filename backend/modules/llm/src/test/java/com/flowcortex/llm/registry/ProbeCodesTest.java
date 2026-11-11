package com.flowcortex.llm.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmJsonResult.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProbeCodesTest {

    @Test
    void anAnsweredCallIsOkWithItsOutputTokensAndNoCode() {
        ProbeResult result = ProbeCodes.of(new LlmJsonResult.Answered("{\"ok\":true}", "m", 12, 7), 340);

        assertThat(result.ok()).isTrue();
        assertThat(result.tested()).isTrue();
        assertThat(result.code()).isNull();
        assertThat(result.latencyMs()).isEqualTo(340);
        assertThat(result.outputTokens()).isEqualTo(7);
        assertThat(result.missingSettings()).isEmpty();
    }

    @Test
    void aSafetyRefusalIsBlocked() {
        ProbeResult result = ProbeCodes.of(new LlmJsonResult.Blocked("SAFETY"), 90);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("blocked");
        assertThat(result.latencyMs()).isEqualTo(90);
        assertThat(result.outputTokens()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "TIMEOUT, timeout",
        "UPSTREAM, upstream",
        "UNREACHABLE, unreachable",
        "QUOTA_EXCEEDED, quota_exceeded",
        "REJECTED, rejected",
        "UNUSABLE, unusable"
    })
    void eachFailureKindKeepsItsLowerCasedName(Kind kind, String code) {
        ProbeResult result = ProbeCodes.of(new LlmJsonResult.Failed(kind), 20_000);

        assertThat(result.ok()).isFalse();
        assertThat(result.tested()).isTrue();
        assertThat(result.code()).isEqualTo(code);
        assertThat(result.latencyMs()).isEqualTo(20_000);
    }

    @Test
    void everyFailureKindIsCoveredByTheTable() {
        assertThat(Kind.values()).hasSize(6);
    }

    @Test
    void aMissingConfigSendsNothingAndNamesTheSettings() {
        ProbeResult result = ProbeResult.missingConfig(List.of("GEMINI_API_KEY"));

        assertThat(result.ok()).isFalse();
        assertThat(result.tested()).isFalse();
        assertThat(result.code()).isEqualTo("missing_config");
        assertThat(result.missingSettings()).containsExactly("GEMINI_API_KEY");
    }
}
