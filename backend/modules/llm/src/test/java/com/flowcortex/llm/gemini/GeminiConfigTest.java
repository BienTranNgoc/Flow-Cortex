package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;

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
}
