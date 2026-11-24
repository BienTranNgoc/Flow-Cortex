package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.workflow.api.RunOptions;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StepContextTest {

    private final MediaJobs media = mock(MediaJobs.class);
    private final UUID runId = UUID.randomUUID();

    private StepContext context(Integer itemIndex, short attempt) {
        return new StepContext(runId, UUID.randomUUID(), 3, "tts", attempt, "in", null, null, RunOptions.DEFAULTS,
                new ArrayList<>(), itemIndex);
    }

    @Test
    void anItemStoresItsFileUnderItsIndexAndAttemptInTheIterateStepsOrder() {
        StepContext item = context(2, (short) 1);
        MediaOutput stored = new MediaOutput("item-2-1-voice.wav", "bucket", "runs/x", "audio/wav", 3L, null);
        when(media.putRunObject(runId, 3, "item-2-1-voice.wav", "audio/wav", new byte[] {1})).thenReturn(stored);

        assertThat(item.store(media, "voice.wav", "audio/wav", new byte[] {1})).isSameAs(stored);

        assertThat(item.storedOutputs()).containsExactly(stored);
    }

    @Test
    void aRegeneratedAttemptStoresUnderItsOwnAttemptSoTheEarlierFileStays() {
        StepContext attemptTwo = context(0, (short) 2);

        attemptTwo.store(media, "voice.wav", "audio/wav", new byte[] {1});

        verify(media).putRunObject(runId, 3, "item-0-2-voice.wav", "audio/wav", new byte[] {1});
    }

    @Test
    void aTopLevelStepKeepsTheNameItAsked() {
        StepContext top = context(null, (short) 1);

        top.store(media, "voice.wav", "audio/wav", new byte[] {1});

        verify(media).putRunObject(runId, 3, "voice.wav", "audio/wav", new byte[] {1});
    }
}
