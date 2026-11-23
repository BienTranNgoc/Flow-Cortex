package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.api.RunInput;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.LocalizedMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class IngestStepExecutorTest {

    private static final String MEDIA = "flowcortex-media";

    private final MediaJobs mediaJobs = Mockito.mock(MediaJobs.class);
    private final UUID runId = UUID.randomUUID();
    private final IngestStepExecutor video = new IngestStepExecutor("ingest_video", "video/", "video", mediaJobs);
    private final IngestStepExecutor image = new IngestStepExecutor("ingest_image", "image/", "image", mediaJobs);

    @Test
    void anUploadedVideoIsCopiedIntoTheStepAndRecordedAsOutputVideo() {
        MediaJobInput source = input("media");
        when(mediaJobs.runInputs(runId)).thenReturn(List.of(bound("media", "video", "video/mp4")));
        MediaOutput copy = output("video", "video/mp4", 4_096L);
        when(mediaJobs.copyRunObject(runId, 1, source, "video")).thenReturn(copy);
        StepContext context = context("ingest_video", stored(source));

        String data = video.execute(context);

        assertThat(context.storedOutputs()).containsExactly(copy);
        assertThat(data).isEqualTo("{\"mime\":\"video/mp4\",\"bytes\":4096}");
    }

    @Test
    void anUploadedImageIsCopiedIntoTheStepAndRecordedAsOutputImage() {
        MediaJobInput source = input("image_1");
        when(mediaJobs.runInputs(runId)).thenReturn(List.of(bound("image_1", "image", "image/png")));
        MediaOutput copy = output("image", "image/png", 900L);
        when(mediaJobs.copyRunObject(runId, 1, source, "image")).thenReturn(copy);
        StepContext context = context("ingest_image", storedList(source));

        String data = image.execute(context);

        assertThat(context.storedOutputs()).containsExactly(copy);
        assertThat(data).isEqualTo("{\"mime\":\"image/png\",\"bytes\":900}");
    }

    @Test
    void anAudioUploadOrTwoVideosAreRefusedBeforeAnythingIsCopied() {
        MediaJobInput first = input("media");
        MediaJobInput second = input("second");
        when(mediaJobs.runInputs(runId)).thenReturn(List.of(bound("media", "audio", "audio/mpeg")));

        assertRejected(video, context("ingest_video", stored(first)), "This step needs exactly one uploaded video");

        when(mediaJobs.runInputs(runId)).thenReturn(
                List.of(bound("media", "video", "video/mp4"), bound("second", "video", "video/webm")));
        assertRejected(video, context("ingest_video", storedList(first, second)),
                "This step needs exactly one uploaded video");
        verify(mediaJobs, never()).copyRunObject(any(), anyInt(), any(), anyString());
    }

    @Test
    void twoImagesAnImageStepGivenAVideoOrAnInputWithoutARegisteredAssetAreRefused() {
        MediaJobInput first = input("image_1");
        MediaJobInput second = input("image_2");
        when(mediaJobs.runInputs(runId)).thenReturn(
                List.of(bound("image_1", "image", "image/png"), bound("image_2", "image", "image/jpeg")));
        assertRejected(image, context("ingest_image", storedList(first, second)),
                "This step needs exactly one uploaded image");

        when(mediaJobs.runInputs(runId)).thenReturn(List.of(bound("media", "video", "video/mp4")));
        assertRejected(image, context("ingest_image", stored(input("media"))),
                "This step needs exactly one uploaded image");

        when(mediaJobs.runInputs(runId)).thenReturn(List.of());
        assertRejected(image, context("ingest_image", stored(first)), "This step needs exactly one uploaded image");
        verify(mediaJobs, never()).copyRunObject(any(), anyInt(), any(), anyString());
    }

    @Test
    void aMissingOrEmptyStoredInputIsRefused() {
        assertRejected(video, context("ingest_video", null), "This step needs exactly one uploaded video");
        assertRejected(video, context("ingest_video", ""), "This step needs exactly one uploaded video");
        assertRejected(video, context("ingest_video", "[]"), "This step needs exactly one uploaded video");
        verify(mediaJobs, never()).copyRunObject(any(), anyInt(), any(), anyString());
    }

    @Test
    void aStoredInputThatIsNotObjectReferencesBreaksTheContract() {
        assertThatThrownBy(() -> video.execute(context("ingest_video", "{not json")))
                .isInstanceOfSatisfying(StepFailedException.class,
                        e -> assertThat(e.code()).isEqualTo("contract_violation"));
    }

    @Test
    void aCopyTheStoreDoesNotTakeFailsTheStepWithoutRecordingAnOutput() {
        MediaJobInput source = input("media");
        when(mediaJobs.runInputs(runId)).thenReturn(List.of(bound("media", "video", "video/mp4")));
        when(mediaJobs.copyRunObject(runId, 1, source, "video"))
                .thenThrow(new MediaStoreUnavailableException("The object store did not take the step's file", null));
        StepContext context = context("ingest_video", stored(source));

        assertThatThrownBy(() -> video.execute(context)).isInstanceOfSatisfying(StepFailedException.class, e -> {
            assertThat(e.code()).isEqualTo("store_unavailable");
            assertThat(e.getMessage()).isEqualTo("Couldn't save the step's files");
        });
        assertThat(context.storedOutputs()).isEmpty();
    }

    @Test
    void anUploadThatVanishedBeforeTheCopyIsRefusedAsTheInput() {
        MediaJobInput source = input("media");
        when(mediaJobs.runInputs(runId)).thenReturn(List.of(bound("media", "video", "video/mp4")));
        when(mediaJobs.copyRunObject(runId, 1, source, "video"))
                .thenThrow(new UploadRejectedException("uploadIds", new LocalizedMessage("upload.again")));

        assertRejected(video, context("ingest_video", stored(source)), "The uploaded file is no longer available");
    }

    @Test
    void theStepsRunInlineOnTheStoredInputWithinAMinute() {
        assertThat(video.type()).isEqualTo("ingest_video");
        assertThat(image.type()).isEqualTo("ingest_image");
        for (IngestStepExecutor executor : List.of(video, image)) {
            assertThat(executor.mode()).isEqualTo(StepExecutor.Mode.INLINE);
            assertThat(executor.inputPolicy()).isEqualTo(StepExecutor.InputPolicy.STORED);
            assertThat(executor.maxRuntime()).isEqualTo(Duration.ofSeconds(60));
        }
        assertThat(IngestStepExecutor.TYPES).containsExactlyInAnyOrder("ingest_video", "ingest_image");
    }

    private static void assertRejected(IngestStepExecutor executor, StepContext context, String message) {
        assertThatThrownBy(() -> executor.execute(context)).isInstanceOfSatisfying(StepFailedException.class, e -> {
            assertThat(e.code()).isEqualTo("input_rejected");
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    private StepContext context(String type, String storedInput) {
        return new StepContext(runId, UUID.randomUUID(), 1, type, (short) 1, null, null, storedInput);
    }

    private MediaJobInput input(String key) {
        return new MediaJobInput(key, MEDIA, "runs/" + runId + "/inputs/" + key);
    }

    private MediaOutput output(String name, String mime, long bytes) {
        return new MediaOutput(name, MEDIA, "runs/" + runId + "/steps/1/" + name, mime, bytes, null);
    }

    private static RunInput bound(String key, String kind, String mime) {
        return new RunInput(key, kind, mime, "https://signed.example/" + key, Instant.parse("2026-11-11T00:00:00Z"));
    }

    private static String stored(MediaJobInput input) {
        return "{\"key\":\"" + input.key() + "\",\"bucket\":\"" + input.bucket() + "\",\"objectKey\":\""
                + input.objectKey() + "\"}";
    }

    private static String storedList(MediaJobInput... inputs) {
        return java.util.Arrays.stream(inputs).map(IngestStepExecutorTest::stored)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
