package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class FetchVideoStepExecutorTest {

    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00Z");
    private static final String LINK_SENTENCE = "The step needs a link that starts with http:// or https://";

    private final MediaJobs mediaJobs = Mockito.mock(MediaJobs.class);
    private final FetchVideoStepExecutor executor =
            new FetchVideoStepExecutor(mediaJobs, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID runId = UUID.randomUUID();
    private final UUID stepId = UUID.randomUUID();

    FetchVideoStepExecutorTest() {
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.limitsFor("fetch_video"))
                .thenReturn(new MediaLimits(Duration.ofMinutes(20), Duration.ofMinutes(10), 500_000_000L));
    }

    @Test
    void theLinkFromTheRunInputIsTrimmedAndSentWithTheDefaultParams() {
        executor.execute(context(" https://cdn.example.com/clip.mp4 ", null));

        MediaJob job = enqueued();
        assertThat(job.capability()).isEqualTo(MediaCapability.FETCH_VIDEO);
        assertThat(job.inputs()).isEmpty();
        assertThat(job.params()).isEqualTo(Map.of("url", "https://cdn.example.com/clip.mp4",
                "quality", "best", "extractAudio", true));
        assertThat(job.maxInputBytes()).isEqualTo(500_000_000L);
        assertThat(job.maxRuntimeSeconds()).isEqualTo(600);
        assertThat(job.jobId()).isEqualTo(stepId);
        assertThat(job.runId()).isEqualTo(runId);
        assertThat(job.correlationId()).isEqualTo(runId);
        assertThat(job.stepOrder()).isEqualTo(2);
        assertThat(job.attempt()).isEqualTo(3);
        assertThat(job.outputPrefix()).isEqualTo("runs/" + runId + "/steps/2/attempts/3/");
        assertThat(job.issuedAt()).isEqualTo(NOW);
    }

    @Test
    void theConfigUrlWinsOverTheInputAndItsQualityAndExtractAudioAreSent() {
        executor.execute(context("hello", "{\"url\":\"https://www.youtube.com/watch?v=abc\","
                + "\"quality\":\"720p\",\"extractAudio\":false}"));

        assertThat(enqueued().params()).isEqualTo(Map.of("url", "https://www.youtube.com/watch?v=abc",
                "quality", "720p", "extractAudio", false));
    }

    @Test
    void aConfigWithoutUrlTakesTheInputAndKeepsItsOtherValues() {
        executor.execute(context("https://cdn.example.com/a.mp4", "{\"quality\":\"audio_only\"}"));

        assertThat(enqueued().params()).isEqualTo(Map.of("url", "https://cdn.example.com/a.mp4",
                "quality", "audio_only", "extractAudio", true));
    }

    @Test
    void anInputThatIsNotALinkIsRefusedAndNothingIsPublished() {
        for (String input : new String[] {"hello", "ftp://x.example/a.mp4", "", null, "https://u:p@x.example/a"}) {
            assertThatThrownBy(() -> executor.execute(context(input, null)))
                    .isInstanceOfSatisfying(StepFailedException.class, e -> {
                        assertThat(e.code()).isEqualTo("input_rejected");
                        assertThat(e.getMessage()).isEqualTo(LINK_SENTENCE);
                    });
        }

        verify(mediaJobs, never()).enqueue(any());
    }

    @Test
    void aConfigThatBreaksItsRulesIsAContractViolationAndNothingIsPublished() {
        assertThatThrownBy(() -> executor.execute(context("https://x.example/a.mp4", "{\"quality\":\"4k\"}")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("contract_violation"));

        verify(mediaJobs, never()).enqueue(any());
    }

    @Test
    void aDisabledMediaEngineFailsTheStepBeforeAnyJobIsBuilt() {
        when(mediaJobs.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> executor.execute(context("https://x.example/a.mp4", null)))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("media_disabled"));

        verify(mediaJobs, never()).enqueue(any());
    }

    @Test
    void aJobThatBreaksItsContractFailsTheStepWithContractViolation() {
        doThrow(new ConstraintViolationException("bad", Set.of())).when(mediaJobs).enqueue(any());

        assertThatThrownBy(() -> executor.execute(context("https://x.example/a.mp4", null)))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("contract_violation"));
    }

    @Test
    void itRunsRemotelyReadsItsInputByReferenceAndBindsNoUpload() {
        assertThat(executor.type()).isEqualTo("fetch_video");
        assertThat(executor.mode()).isEqualTo(StepExecutor.Mode.REMOTE);
        assertThat(executor.inputPolicy()).isEqualTo(StepExecutor.InputPolicy.RESOLVED);
        assertThat(executor.bindsRunInputs()).isFalse();
        assertThat(executor.maxRuntime()).isEqualTo(Duration.ofMinutes(10));
        assertThat(executor.queueTimeout(Duration.ofSeconds(120))).isEqualTo(Duration.ofMinutes(20));
    }

    private MediaJob enqueued() {
        ArgumentCaptor<MediaJob> job = ArgumentCaptor.forClass(MediaJob.class);
        verify(mediaJobs).enqueue(job.capture());
        return job.getValue();
    }

    private StepContext context(String input, String config) {
        return new StepContext(runId, stepId, 2, "fetch_video", (short) 3, input, config, null);
    }
}
