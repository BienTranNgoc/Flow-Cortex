package com.flowcortex.workflow.application;

import static com.flowcortex.workflow.application.DubFixtures.context;
import static com.flowcortex.workflow.application.DubFixtures.dub;
import static com.flowcortex.workflow.application.DubFixtures.slots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** A failed step logs neither the transcript nor the provider's message. */
@ExtendWith(OutputCaptureExtension.class)
class DubTelemetryTest {

    private static final String ZH_1 = "你好朋友们";
    private static final String ZH_2 = "今天我们去吃饭";
    private static final String TRANSCRIPT = "{\"segments\":["
            + "{\"index\":1,\"startMs\":500,\"endMs\":3100,\"zh\":\"" + ZH_1 + "\"},"
            + "{\"index\":2,\"startMs\":5000,\"endMs\":9800,\"zh\":\"" + ZH_2 + "\"}]}";

    @Test
    void aFailedStepLogsNeitherTheTextNorTheProvidersMessage(CapturedOutput output) {
        LlmSlots llm = slots();
        when(llm.generateJson(eq("dub-translate"), anyString(), anyString()))
                .thenReturn(new LlmJsonResult.Failed(LlmJsonResult.Kind.REJECTED));

        assertThatThrownBy(() -> new TranslateStepExecutor(llm, dub())
                .execute(context("translate", 4, TRANSCRIPT, "female")))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("provider_unavailable");
                    assertThat(e.getMessage()).doesNotContain(ZH_1).doesNotContain(ZH_2);
                });
        assertThat(output).doesNotContain(ZH_1).doesNotContain(ZH_2);
    }
}
