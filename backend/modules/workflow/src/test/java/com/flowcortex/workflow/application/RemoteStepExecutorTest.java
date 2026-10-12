package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.media.api.MediaJobs;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RemoteStepExecutorTest {

    private final RemoteStepExecutor executor =
            new RemoteStepExecutor("render_video", Mockito.mock(MediaJobs.class), Clock.systemUTC());

    @Test
    void combinesOutputsOnlyWhenTheStepConfigDeclaresThem() {
        assertThat(executor.inputPolicy()).isEqualTo(StepExecutor.InputPolicy.STORED);
        assertThat(executor.inputPolicy("{\"profile\":\"vertical_ad_v1\"}"))
                .isEqualTo(StepExecutor.InputPolicy.STORED);
        assertThat(executor.inputPolicy("{\"outputInputs\":[]}"))
                .isEqualTo(StepExecutor.InputPolicy.STORED_WITH_OUTPUTS);
    }
}
