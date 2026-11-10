package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.api.LlmSlots;
import com.flowcortex.llm.api.LlmTimeouts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GeminiConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(GeminiConfig.class);

    @Test
    void theAnswerTimeoutDefaultsToTwentySecondsAndIsConfigurable() {
        runner.run(context -> assertThat(context.getBean(LlmTimeouts.class).answer())
                .isEqualTo(Duration.ofSeconds(20)));
        runner.withPropertyValues("flowcortex.llm.gemini.answer-timeout=35s")
                .run(context -> assertThat(context.getBean(LlmTimeouts.class).answer())
                        .isEqualTo(Duration.ofSeconds(35)));
    }

    @Test
    void slotsBindUnderTheGeminiBlockAndFeedTheSlotPort() {
        runner.withPropertyValues(
                "flowcortex.llm.gemini.slots.dub-translate.models=m1,m2",
                "flowcortex.llm.gemini.slots.dub-translate.attempts=3",
                "flowcortex.llm.gemini.slots.dub-translate.retry-waits=10s,30s",
                "flowcortex.llm.gemini.slots.dub-translate.call-timeout=60s").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LlmSlots.class).jsonWorstCase("dub-translate"))
                            .isEqualTo(Duration.ofSeconds(440));
                    assertThat(context.getBean(LlmSlots.class).configured()).isFalse();
                    assertThat(context.getBean("geminiHttpClient")).isInstanceOf(java.net.http.HttpClient.class);
                });
    }

    @Test
    void aSlotWithTheWrongNumberOfWaitsStopsStartupNamingTheSlot() {
        runner.withPropertyValues(
                "flowcortex.llm.gemini.slots.dub-speech.models=m1",
                "flowcortex.llm.gemini.slots.dub-speech.attempts=3",
                "flowcortex.llm.gemini.slots.dub-speech.retry-waits=10s",
                "flowcortex.llm.gemini.slots.dub-speech.call-timeout=60s").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("dub-speech")
                            .hasStackTraceContaining("2 waits");
                });
    }
}
