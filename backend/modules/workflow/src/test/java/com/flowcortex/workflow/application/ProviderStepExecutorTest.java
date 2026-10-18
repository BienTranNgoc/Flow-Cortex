package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.media.api.provider.ProviderInput;
import com.flowcortex.media.api.provider.ProviderJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProviderStepExecutorTest {

    private final MediaJobs jobs = mock(MediaJobs.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void scriptJobCarriesOnlyConfirmedProductPayload() {
        ProviderStepExecutor executor = executor("generate_script", "product_ad_v1");
        StepContext context = context("""
                {"productName":"Bình giữ nhiệt","description":"Giữ đồ uống","cta":"Mua ngay"}
                """, "{\"profile\":\"product_ad_v1\"}");

        executor.execute(context);

        ArgumentCaptor<ProviderJob> sent = ArgumentCaptor.forClass(ProviderJob.class);
        verify(jobs).enqueueProvider(sent.capture());
        assertThat(sent.getValue().capability()).isEqualTo("generate_script");
        assertThat(sent.getValue().params()).containsEntry("profile", "product_ad_v1");
        assertThat(castPayload(sent.getValue())).containsOnlyKeys("productName", "description", "cta");
    }

    @Test
    void scriptJobCarriesTheFirstSnapshottedProductImage() {
        ProviderStepExecutor executor = executor("generate_script", "product_ad_v1");
        StepContext context = context("""
                {"productName":"Bình giữ nhiệt","description":"Giữ đồ uống"}
                """, """
                [{"key":"image_1","bucket":"flowcortex-media","objectKey":"runs/r/inputs/image_1"},
                 {"key":"image_2","bucket":"flowcortex-media","objectKey":"runs/r/inputs/image_2"}]
                """, "{\"profile\":\"product_ad_v1\"}");

        executor.execute(context);

        ArgumentCaptor<ProviderJob> sent = ArgumentCaptor.forClass(ProviderJob.class);
        verify(jobs).enqueueProvider(sent.capture());
        assertThat(sent.getValue().inputs()).containsExactly(
                new ProviderInput("image_1", "flowcortex-media", "runs/r/inputs/image_1"));
    }

    @Test
    void storyboardJobCarriesTheFirstSnapshottedProductImage() {
        ProviderStepExecutor executor = executor("generate_storyboard", "product_ad_storyboard_v1");
        StepContext context = context("""
                {"script":"Kịch bản đã duyệt","sourceRevisionId":"00000000-0000-0000-0000-000000000001"}
                """, """
                [{"key":"image_1","bucket":"flowcortex-media","objectKey":"runs/r/inputs/image_1"}]
                """, "{\"profile\":\"product_ad_storyboard_v1\"}");

        executor.execute(context);

        ArgumentCaptor<ProviderJob> sent = ArgumentCaptor.forClass(ProviderJob.class);
        verify(jobs).enqueueProvider(sent.capture());
        assertThat(sent.getValue().inputs()).containsExactly(
                new ProviderInput("image_1", "flowcortex-media", "runs/r/inputs/image_1"));
    }

    @Test
    void speechJobWrapsApprovedScript() {
        ProviderStepExecutor executor = executor("generate_speech", "vi_voice_v1");
        UUID revisionId = UUID.randomUUID();
        executor.execute(context("""
                {"script":"Kịch bản đã duyệt","sourceRevisionId":"%s"}
                """.formatted(revisionId), "{\"profile\":\"vi_voice_v1\"}"));

        ArgumentCaptor<ProviderJob> sent = ArgumentCaptor.forClass(ProviderJob.class);
        verify(jobs).enqueueProvider(sent.capture());
        assertThat(castPayload(sent.getValue())).containsEntry("script", "Kịch bản đã duyệt");
        assertThat(castPayload(sent.getValue())).containsEntry("sourceRevisionId", revisionId.toString());
    }

    @Test
    void videoJobAppendsConfiguredVisualDirection() {
        ProviderStepExecutor executor = executor("generate_video", "vertical_ad_clip_v1");
        UUID revisionId = UUID.randomUUID();
        executor.execute(context("""
                {"script":"Approved copy","sourceRevisionId":"%s"}
                """.formatted(revisionId), """
                {"profile":"vertical_ad_clip_v1",
                 "payloadFields":{"prompt":"script","sourceRevisionId":"sourceRevisionId"},
                 "promptInstruction":"Opening product hero shot"}
                """));

        ArgumentCaptor<ProviderJob> sent = ArgumentCaptor.forClass(ProviderJob.class);
        verify(jobs).enqueueProvider(sent.capture());
        assertThat(castPayload(sent.getValue()).get("prompt").toString())
                .contains("Approved copy")
                .contains("Visual direction: Opening product hero shot");
        assertThat(castPayload(sent.getValue()))
                .containsEntry("sourceRevisionId", revisionId.toString());
    }

    @Test
    void videoJobBindsStoredInputForVideo2() {
        ProviderStepExecutor executor = executor("generate_video", "vertical_ad_clip_v1");
        UUID revisionId = UUID.randomUUID();
        executor.execute(context("""
                {"scene2Prompt":"Lifestyle on road","sourceRevisionId":"%s"}
                """.formatted(revisionId), """
                [{"key":"image_1","bucket":"flowcortex-uploads","objectKey":"image.jpg"}]
                """, """
                {"profile":"vertical_ad_clip_v1",
                 "payloadFields":{"prompt":"scene2Prompt","sourceRevisionId":"sourceRevisionId"}}
                """));

        ArgumentCaptor<ProviderJob> sent = ArgumentCaptor.forClass(ProviderJob.class);
        verify(jobs).enqueueProvider(sent.capture());
        assertThat(sent.getValue().inputs()).hasSize(1);
        assertThat(sent.getValue().inputs().getFirst().key()).isEqualTo("image_1");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> castPayload(ProviderJob job) {
        return (java.util.Map<String, Object>) job.params().get("payload");
    }

    @Test
    void aVideoStepRunsOnTheVideoLimitsAndOtherStepsOnTheProviderLimits() {
        when(jobs.providerLimits("generate_video"))
                .thenReturn(new MediaLimits(Duration.ofMinutes(15), Duration.ofMinutes(10), 1000L));
        ProviderStepExecutor video = new ProviderStepExecutor("generate_video", "vertical_ad_clip_v1", jobs, clock);
        ProviderStepExecutor script = executor("generate_script", "product_ad_v1");

        assertThat(video.maxRuntime()).isEqualTo(Duration.ofMinutes(10));
        assertThat(video.queueTimeout(Duration.ofMinutes(1))).isEqualTo(Duration.ofMinutes(15));
        assertThat(script.maxRuntime()).isEqualTo(Duration.ofMinutes(2));
        assertThat(script.queueTimeout(Duration.ofMinutes(1))).isEqualTo(Duration.ofMinutes(5));

        video.execute(context("{\"script\":\"Approved copy\",\"sourceRevisionId\":\"%s\"}"
                .formatted(UUID.randomUUID()), "{\"profile\":\"vertical_ad_clip_v1\","
                + "\"payloadFields\":{\"prompt\":\"script\",\"sourceRevisionId\":\"sourceRevisionId\"}}"));
        ArgumentCaptor<ProviderJob> sent = ArgumentCaptor.forClass(ProviderJob.class);
        verify(jobs).enqueueProvider(sent.capture());
        assertThat(sent.getValue().maxRuntimeSeconds()).isEqualTo(600);
    }

    private ProviderStepExecutor executor(String capability, String profile) {
        when(jobs.providerLimits(capability))
                .thenReturn(new MediaLimits(Duration.ofMinutes(5), Duration.ofMinutes(2), 1000L));
        return new ProviderStepExecutor(capability, profile, jobs, clock);
    }

    @Test
    void videoJobSendsTheProductImageTheConfigNamesFromTheRunInputs() {
        ProviderStepExecutor executor = executor("generate_video", "vertical_ad_clip_v1");
        UUID runId = UUID.randomUUID();

        executor.execute(videoContext(runId, storedImages(runId, "image_1", "image_2")));

        ArgumentCaptor<ProviderJob> sent = ArgumentCaptor.forClass(ProviderJob.class);
        verify(jobs).enqueueProvider(sent.capture());
        assertThat(sent.getValue().inputs())
                .containsExactly(new ProviderInput("image_1", "media", "runs/" + runId + "/inputs/image_1"));
    }

    @Test
    void videoJobWithoutTheNamedImageInTheRunInputsFails() {
        ProviderStepExecutor executor = executor("generate_video", "vertical_ad_clip_v1");
        UUID runId = UUID.randomUUID();

        for (String stored : new String[] {storedImages(runId, "image_2"), null, "not json"}) {
            assertThatThrownBy(() -> executor.execute(videoContext(runId, stored)))
                    .isInstanceOf(StepFailedException.class)
                    .hasMessage("Product image image_1 is missing from the run inputs")
                    .extracting("code").isEqualTo("contract_violation");
        }
        verify(jobs, never()).enqueueProvider(any());
    }

    @Test
    void videoJobRefusesAProductImageOutsideItsOwnRunInputs() {
        ProviderStepExecutor executor = executor("generate_video", "vertical_ad_clip_v1");
        UUID runId = UUID.randomUUID();

        assertThatThrownBy(() -> executor.execute(videoContext(runId, storedImages(UUID.randomUUID(), "image_1"))))
                .isInstanceOf(StepFailedException.class)
                .hasMessage("Product image image_1 is missing from the run inputs");
        verify(jobs, never()).enqueueProvider(any());
    }

    private static StepContext videoContext(UUID runId, String storedInput) {
        return new StepContext(runId, UUID.randomUUID(), 4, "generate_video", (short) 1,
                "{\"scene1Prompt\":\"Opening hero scene\",\"sourceRevisionId\":\"%s\"}".formatted(UUID.randomUUID()),
                "{\"profile\":\"vertical_ad_clip_v1\",\"referenceImage\":\"image_1\","
                        + "\"payloadFields\":{\"prompt\":\"scene1Prompt\",\"sourceRevisionId\":\"sourceRevisionId\"}}",
                storedInput);
    }

    private static String storedImages(UUID runId, String... keys) {
        return java.util.Arrays.stream(keys)
                .map(key -> "{\"key\":\"%s\",\"bucket\":\"media\",\"objectKey\":\"runs/%s/inputs/%s\"}"
                        .formatted(key, runId, key))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static StepContext context(String input, String config) {
        return context(input, null, config);
    }

    private static StepContext context(String input, String storedInput, String config) {
        UUID runId = UUID.randomUUID();
        return new StepContext(runId, UUID.randomUUID(), 1, "provider", (short) 1, input, config, storedInput);
    }
}
