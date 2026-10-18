package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RemoteStepExecutorTest {

    private final MediaJobs mediaJobs = Mockito.mock(MediaJobs.class);
    private final RemoteStepExecutor executor =
            new RemoteStepExecutor("render_video", mediaJobs, Clock.systemUTC());

    @Test
    void combinesOutputsOnlyWhenTheStepConfigDeclaresThem() {
        assertThat(executor.inputPolicy()).isEqualTo(StepExecutor.InputPolicy.STORED);
        assertThat(executor.inputPolicy("{\"profile\":\"vertical_ad_v1\"}"))
                .isEqualTo(StepExecutor.InputPolicy.STORED);
        assertThat(executor.inputPolicy("{\"outputInputs\":[]}"))
                .isEqualTo(StepExecutor.InputPolicy.STORED_WITH_OUTPUTS);
    }

    @Test
    void theInputCeilingIsTheBoundAssetsPlusEachConfiguredOutputInput() {
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.limitsFor("render_video"))
                .thenReturn(new MediaLimits(Duration.ofMinutes(30), Duration.ofMinutes(10), 1_000L));
        String twoOutputs = """
                {"outputInputs":[
                  {"stepOrder":2,"outputName":"speech.wav","inputKey":"speech"},
                  {"stepOrder":3,"outputName":"music.wav","inputKey":"music"}]}""";

        executor.execute(context(inputs(12), twoOutputs));

        ArgumentCaptor<MediaJob> sent = ArgumentCaptor.forClass(MediaJob.class);
        verify(mediaJobs).enqueue(sent.capture());
        assertThat(sent.getValue().inputs()).hasSize(12);
        assertThatThrownBy(() -> executor.execute(context(inputs(13), twoOutputs)))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("between 1 and 12");
        assertThatThrownBy(() -> executor.execute(context(inputs(11), "{\"profile\":\"vertical_ad_v1\"}")))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("between 1 and 10");
    }

    private static StepContext context(String input, String config) {
        return new StepContext(UUID.randomUUID(), UUID.randomUUID(), 4, "render_video", (short) 1, input, input,
                config);
    }

    private static String inputs(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> "{\"key\":\"image_" + i + "\",\"bucket\":\"flowcortex-media\",\"objectKey\":\"k" + i + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
