package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.BoundRunInput;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.api.StoredMediaFile;
import com.flowcortex.media.domain.MediaAsset;
import com.flowcortex.media.domain.MediaAssetBinding;
import com.flowcortex.media.infrastructure.MediaAssetBindingRepository;
import com.flowcortex.media.infrastructure.MediaAssetRepository;
import com.flowcortex.media.infrastructure.S3MediaStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

class RegistrableMediaImplTest {

    private static final String BUCKET = "flowcortex-media";

    private final S3MediaStore store = mock(S3MediaStore.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final MediaAssetBindingRepository bindings = mock(MediaAssetBindingRepository.class);
    private final MediaProperties properties = mock(MediaProperties.class, RETURNS_DEEP_STUBS);
    private final RegistrableMediaImpl media;

    RegistrableMediaImplTest() {
        when(properties.s3().bucket()).thenReturn(BUCKET);
        media = new RegistrableMediaImpl(store, assets, bindings, properties);
    }

    private static String copyKey(UUID runId, String bindingKey) {
        return "runs/" + runId + "/inputs/" + bindingKey;
    }

    private void givenCopyOf(String key, long bytes) {
        when(store.head(BUCKET, key)).thenReturn(Optional.of(new S3MediaStore.StoredObject(bytes, "image/png", null)));
    }

    @Test
    void keepRetagsARunInputCopyAsAssetAndReportsTheCopysOwnSize() {
        String key = copyKey(UUID.randomUUID(), "media");
        givenCopyOf(key, 4_321L);

        assertThat(media.keepRunInput(BUCKET, key)).hasValue(4_321L);

        verify(store).retag(BUCKET, key, "asset");
    }

    @Test
    void keepReportsTheCopysSizeNotTheUploadsWhenTheyDiffer() {
        UUID runId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(bindings.findByRunIdOrderByPositionAsc(runId)).thenReturn(List.of(
                new MediaAssetBinding(runId, "image_1", uploadId, (short) 0, Instant.now())));
        when(assets.findById(uploadId)).thenReturn(Optional.of(upload(uploadId, owner, "image/png")));
        String key = media.boundInputs(runId).getFirst().objectKey();
        givenCopyOf(key, 5L);

        assertThat(media.keepRunInput(BUCKET, key)).hasValue(5L);
    }

    @Test
    void keepReportsMissingWhenTheCopyIsGone() {
        String key = copyKey(UUID.randomUUID(), "media");
        when(store.head(BUCKET, key)).thenReturn(Optional.empty());

        assertThat(media.keepRunInput(BUCKET, key)).isEmpty();

        verify(store, never()).retag(anyString(), anyString(), anyString());
    }

    @Test
    void keepReportsMissingWhenTheCopyVanishesBeforeTheRetag() {
        String key = copyKey(UUID.randomUUID(), "media");
        givenCopyOf(key, 9L);
        doThrow(NoSuchKeyException.builder().build()).when(store).retag(BUCKET, key, "asset");

        assertThat(media.keepRunInput(BUCKET, key)).isEmpty();
    }

    @Test
    void keepTurnsAnyOtherStoreFailureIntoStoreUnavailable() {
        String key = copyKey(UUID.randomUUID(), "media");
        givenCopyOf(key, 9L);
        doThrow(AwsServiceException.builder().message("slow down").build()).when(store).retag(BUCKET, key, "asset");

        assertThatThrownBy(() -> media.keepRunInput(BUCKET, key)).isInstanceOf(MediaStoreUnavailableException.class);
    }

    @Test
    void keepRefusesAKeyOutsideARunsInputs() {
        UUID runId = UUID.randomUUID();
        for (String key : List.of("runs/" + runId + "/steps/1/x.mp4", "support/" + runId + "/a.png",
                UUID.randomUUID() + "/" + UUID.randomUUID(), "runs/" + runId + "/inputs/a/b",
                "runs/not-a-uuid/inputs/media")) {
            assertThatThrownBy(() -> media.keepRunInput(BUCKET, key)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> media.keepRunInput("flowcortex-uploads", copyKey(runId, "media")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).retag(anyString(), anyString(), anyString());
    }

    @Test
    void releaseRetagsARunInputCopyBackToRunInput() {
        String key = copyKey(UUID.randomUUID(), "media");

        media.releaseRunInput(BUCKET, key);

        verify(store).retag(BUCKET, key, "run-input");
    }

    @Test
    void releaseIgnoresACopyThatIsAlreadyGone() {
        String key = copyKey(UUID.randomUUID(), "media");
        doThrow(NoSuchKeyException.builder().build()).when(store).retag(BUCKET, key, "run-input");

        media.releaseRunInput(BUCKET, key);
    }

    @Test
    void releaseTurnsAnyOtherStoreFailureIntoStoreUnavailable() {
        String key = copyKey(UUID.randomUUID(), "media");
        doThrow(AwsServiceException.builder().message("slow down").build()).when(store).retag(BUCKET, key,
                "run-input");

        assertThatThrownBy(() -> media.releaseRunInput(BUCKET, key))
                .isInstanceOf(MediaStoreUnavailableException.class);
    }

    @Test
    void releaseRefusesAKeyOutsideARunsInputs() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> media.releaseRunInput(BUCKET, "runs/" + runId + "/steps/1/x.mp4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> media.releaseRunInput("flowcortex-uploads", copyKey(runId, "media")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).retag(anyString(), anyString(), anyString());
    }

    @Test
    void stepOutputsFlagObjectsOutsideTheMediaBucket() {
        UUID stepId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        MediaAsset inside = new MediaAsset(UUID.randomUUID(), owner, UUID.randomUUID(), "video", BUCKET,
                "runs/r/steps/1/b.mp4", "video/mp4", 10L, null, stepId, (short) 1, Instant.now());
        MediaAsset outside = new MediaAsset(UUID.randomUUID(), owner, UUID.randomUUID(), "image",
                "flowcortex-uploads", "o/a.png", "image/png", 5L, null, stepId, (short) 1, Instant.now());
        when(assets.findBySourceRunStepIdAndSourceAttempt(stepId, (short) 1)).thenReturn(List.of(inside, outside));

        List<StoredMediaFile> files = media.stepOutputs(stepId, (short) 1);

        assertThat(files).extracting(StoredMediaFile::objectKey).containsExactly("o/a.png", "runs/r/steps/1/b.mp4");
        assertThat(files).extracting(StoredMediaFile::inMediaBucket).containsExactly(false, true);
        assertThat(files).extracting(StoredMediaFile::ownerId).containsOnly(owner);
    }

    @Test
    void boundInputsComeInPositionOrderWithTheRunCopyKey() {
        UUID runId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(bindings.findByRunIdOrderByPositionAsc(runId)).thenReturn(List.of(
                new MediaAssetBinding(runId, "image_1", first, (short) 0, Instant.now()),
                new MediaAssetBinding(runId, "image_2", second, (short) 1, Instant.now())));
        when(assets.findById(first)).thenReturn(Optional.of(upload(first, owner, "image/png")));
        when(assets.findById(second)).thenReturn(Optional.of(upload(second, owner, "image/webp")));

        List<BoundRunInput> inputs = media.boundInputs(runId);

        assertThat(inputs).extracting(BoundRunInput::bindingKey).containsExactly("image_1", "image_2");
        assertThat(inputs).extracting(BoundRunInput::objectKey)
                .containsExactly(copyKey(runId, "image_1"), copyKey(runId, "image_2"));
        assertThat(inputs).extracting(BoundRunInput::bucket).containsOnly(BUCKET);
        assertThat(inputs).extracting(BoundRunInput::mediaAssetId).containsExactly(first, second);
    }

    private static MediaAsset upload(UUID id, UUID owner, String mime) {
        return new MediaAsset(id, owner, UUID.randomUUID(), "image", "flowcortex-uploads", owner + "/" + id, mime,
                7L, null, Instant.now());
    }
}
