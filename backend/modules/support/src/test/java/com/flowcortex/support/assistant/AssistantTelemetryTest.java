package com.flowcortex.support.assistant;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.support.assistant.TopicGuard.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AssistantTelemetryTest {

    @Test
    void aHandoffTicksItsTopicAndLogsNoText(CapturedOutput output) {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AssistantTelemetry telemetry = new AssistantTelemetry(meters, TestObservationRegistry.create());

        telemetry.handoff(Topic.MONEY);
        telemetry.handoff(Topic.MONEY);
        telemetry.handoff(Topic.LEGAL);
        telemetry.record("handoff", 0, 31, System.nanoTime());

        org.assertj.core.api.Assertions.assertThat(meters.counter(AssistantTelemetry.HANDOFF_COUNTER, "topic", "money")
                .count()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(meters.counter(AssistantTelemetry.HANDOFF_COUNTER, "topic", "legal")
                .count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(output.getAll())
                .contains("support.assistant.handoff topic=money", "topic=legal")
                .doesNotContain("trừ tiền");
    }

    @Test
    void aHandoffWithoutATopicTicksAndLogsNothing(CapturedOutput output) {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new AssistantTelemetry(meters, TestObservationRegistry.create()).handoff(null);

        org.assertj.core.api.Assertions.assertThat(meters.getMeters()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("support.assistant.handoff");
    }

    @Test
    void theProviderAndItsTokensLandOnTheCurrentObservation() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        AssistantTelemetry telemetry = new AssistantTelemetry(new SimpleMeterRegistry(), registry);

        Observation request = Observation.start("http.server.requests", registry);
        try (Observation.Scope scope = request.openScope()) {
            telemetry.provider("support-assistant", new LlmJsonResult.Answered("{}", "gemini-test", 120, 30), 2);
        } finally {
            request.stop();
        }

        assertThat(registry).hasObservationWithNameEqualTo("http.server.requests").that()
                .hasHighCardinalityKeyValue("gen_ai.system", "gcp.gemini")
                .hasHighCardinalityKeyValue("flowcortex.llm.slot", "support-assistant")
                .hasHighCardinalityKeyValue("gen_ai.response.model", "gemini-test")
                .doesNotHaveHighCardinalityKeyValueWithKey("gen_ai.request.model")
                .hasHighCardinalityKeyValue("gen_ai.usage.input_tokens", "120")
                .hasHighCardinalityKeyValue("gen_ai.usage.output_tokens", "30")
                .hasHighCardinalityKeyValue("support.assistant.sources", "2");
    }

    @Test
    void withoutACurrentObservationNothingIsTagged() {
        TestObservationRegistry registry = TestObservationRegistry.create();

        new AssistantTelemetry(new SimpleMeterRegistry(), registry)
                .provider("support-assistant", new LlmJsonResult.Blocked("SAFETY"), 0);

        assertThat(registry).doesNotHaveAnyObservation();
    }
}
