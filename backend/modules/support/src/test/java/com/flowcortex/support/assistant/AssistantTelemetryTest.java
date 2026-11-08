package com.flowcortex.support.assistant;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;

import com.flowcortex.llm.api.LlmJsonResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;

class AssistantTelemetryTest {

    @Test
    void theProviderAndItsTokensLandOnTheCurrentObservation() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        AssistantTelemetry telemetry = new AssistantTelemetry(new SimpleMeterRegistry(), registry);

        Observation request = Observation.start("http.server.requests", registry);
        try (Observation.Scope scope = request.openScope()) {
            telemetry.provider("gemini-test", new LlmJsonResult.Answered("{}", "gemini-test", 120, 30), 2);
        } finally {
            request.stop();
        }

        assertThat(registry).hasObservationWithNameEqualTo("http.server.requests").that()
                .hasHighCardinalityKeyValue("gen_ai.system", "gcp.gemini")
                .hasHighCardinalityKeyValue("gen_ai.request.model", "gemini-test")
                .hasHighCardinalityKeyValue("gen_ai.usage.input_tokens", "120")
                .hasHighCardinalityKeyValue("gen_ai.usage.output_tokens", "30")
                .hasHighCardinalityKeyValue("support.assistant.sources", "2");
    }

    @Test
    void withoutACurrentObservationNothingIsTagged() {
        TestObservationRegistry registry = TestObservationRegistry.create();

        new AssistantTelemetry(new SimpleMeterRegistry(), registry)
                .provider("gemini-test", new LlmJsonResult.Blocked("SAFETY"), 0);

        assertThat(registry).doesNotHaveAnyObservation();
    }
}
