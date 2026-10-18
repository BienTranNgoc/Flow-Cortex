package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.ProviderJob;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.media.infrastructure.S3MediaStore;
import com.flowcortex.media.infrastructure.MediaAssetBindingRepository;
import com.flowcortex.media.infrastructure.MediaAssetRepository;
import com.flowcortex.media.domain.MediaAsset;
import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs {@link MediaJobsImpl} in a small Spring context — real Bean Validation, real property
 * binding under {@code flowcortex.media}, a real transactional proxy — and captures the events it
 * publishes.
 */
@ExtendWith(OutputCaptureExtension.class)
class MediaJobsImplTest {

    private static final String SIZE_SENTENCE =
            "Files of this type must be at most 500 MB. Accepted: .mp4, .mov, .webm, .mkv.";
    private static final String AUDIO_SIZE_SENTENCE =
            "Files of this type must be at most 100 MB. Accepted: .mp3, .wav, .m4a.";
    private static final String IMAGE_SIZE_SENTENCE =
            "Files of this type must be at most 10 MB. Accepted: .jpg, .jpeg, .png, .webp.";
    private static final byte[] MP4_PREFIX = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    private static final byte[] PNG_PREFIX = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final String TYPE_SENTENCE =
            "This file type can't be checked yet. Use .mp4, .mov, .webm, .mkv, .m4a, .mp3, .wav, .jpg, .jpeg, .png or .webp.";
    private static final String MEDIA_TYPE_SENTENCE =
            "This file type can't be checked yet. Use .mp4, .mov, .webm, .mkv, .m4a, .mp3 or .wav.";

    private static final String[] VALID_PROPERTIES = {
        "flowcortex.media.enabled=true",
        "flowcortex.media.queue-timeout=30m",
        "flowcortex.media.max-runtime.probe=5m",
        "flowcortex.media.max-runtime.extract_audio=10m",
        "flowcortex.media.max-runtime.align_audio=10m",
        "flowcortex.media.max-runtime.render_video=10m",
        "flowcortex.media.consumer-timeout=20m",
        "flowcortex.media.provider-max-runtime=2m",
        "flowcortex.media.provider-queue-timeout=5m",
        "flowcortex.media.provider-video-max-runtime=10m",
        "flowcortex.media.provider-video-queue-timeout=15m",
        "flowcortex.media.presign-ttl=15m",
        "flowcortex.media.upload.max-video-bytes=524288000",
        "flowcortex.media.upload.max-audio-bytes=104857600",
        "flowcortex.media.upload.max-image-bytes=10485760",
        "flowcortex.media.upload.content-check.enabled=true",
        "flowcortex.media.upload.extensions=mp4,MOV,webm,mkv,m4a,mp3,wav",
        "flowcortex.media.upload.product-image-extensions=jpg,jpeg,png,webp",
        "flowcortex.media.upload.url-ttl=15m",
        "flowcortex.media.s3.endpoint=",
        "flowcortex.media.s3.public-endpoint=",
        "flowcortex.media.s3.region=us-east-1",
        "flowcortex.media.s3.path-style=true",
        "flowcortex.media.s3.upload-bucket=flowcortex-uploads",
        "flowcortex.media.s3.bucket=flowcortex-media",
        "flowcortex.media.s3.access-key=backend-key",
        "flowcortex.media.s3.secret-key=backend-secret-value",
        "flowcortex.media.s3.api-call-timeout=5s",
        "flowcortex.media.s3.api-call-attempt-timeout=2s",
        "flowcortex.media.s3.copy-timeout=2m",
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class, MediaConfig.class, MediaJobsImpl.class)
            .withPropertyValues(VALID_PROPERTIES);

    private static MediaJob job(MediaCapability capability) {
        UUID runId = UUID.randomUUID();
        return new MediaJob("1", UUID.randomUUID(), runId, runId, 1, capability,
                List.of(new MediaJobInput("source", "flowcortex-uploads", "u-1/up-1")),
                Map.of(), "runs/" + runId + "/steps/1/", 1, 300, 524_288_000L, Instant.now());
    }

    @Test
    void jobWithoutCapabilityIsRefusedAndNothingIsPublished() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.enqueue(job(null))))
                    .isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("capability");
            assertThat(context.getBean(EventCapture.class).events).isEmpty();
        });
    }

    @Test
    void validJobPublishesExactlyOneMediaJobRequestedInsideTheCallersTransaction() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            MediaJob job = job(MediaCapability.PROBE);

            tx.executeWithoutResult(status -> jobs.enqueue(job));

            EventCapture capture = context.getBean(EventCapture.class);
            assertThat(capture.events).containsExactly(new MediaJobRequested(job));
            assertThat(capture.publishedInTransaction).containsExactly(true);
        });
    }

    @Test
    void enqueueOutsideATransactionIsRefusedAndNothingIsPublished() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);

            assertThatThrownBy(() -> jobs.enqueue(job(MediaCapability.PROBE)))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(context.getBean(EventCapture.class).events).isEmpty();
        });
    }

    private static ProviderJob providerJob(String capability, Map<String, Object> params) {
        UUID runId = UUID.randomUUID();
        return new ProviderJob("1", UUID.randomUUID(), runId, runId, 1, capability, params,
                List.of(), "runs/" + runId + "/steps/1/attempts/1/", 1, 120, Instant.now());
    }

    private static Map<String, Object> scriptParams(Map<String, Object> payload) {
        return Map.of("profile", "product_ad_v1", "payload", payload);
    }

    @Test
    void validProviderJobPublishesExactlyOneProviderJobRequested() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            ProviderJob job = providerJob("generate_script", scriptParams(Map.of(
                    "productName", "Máy lọc nước", "description", "Lọc sạch", "cta", "Mua ngay")));

            tx.executeWithoutResult(status -> jobs.enqueueProvider(job));

            EventCapture capture = context.getBean(EventCapture.class);
            assertThat(capture.providerEvents).containsExactly(new ProviderJobRequested(job));
        });
    }

    @Test
    void providerJobWhosePayloadBreaksItsContractIsRefusedAndNothingIsPublished() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.enqueueProvider(
                    providerJob("generate_script", scriptParams(Map.of(
                            "productName", "x".repeat(201), "description", "Lọc sạch", "cta", "Mua ngay"))))))
                    .isInstanceOf(ConstraintViolationException.class);
            assertThat(context.getBean(EventCapture.class).providerEvents).isEmpty();
        });
    }

    @Test
    void speechProviderJobWhosePayloadHasNoScriptIsRefused() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.enqueueProvider(
                    providerJob("generate_speech", Map.of("profile", "vi_voice_v1", "payload", Map.of())))))
                    .isInstanceOf(ConstraintViolationException.class);
            assertThat(context.getBean(EventCapture.class).providerEvents).isEmpty();
        });
    }

    @Test
    void providerJobWithAnOutputPrefixThatIsNotAttemptScopedIsRefused() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID runId = UUID.randomUUID();
            ProviderJob legacy = new ProviderJob("1", UUID.randomUUID(), runId, runId, 1, "generate_script",
                    scriptParams(Map.of("productName", "Máy lọc nước", "description", "Lọc sạch", "cta", "Mua ngay")),
                    List.of(), "runs/" + runId + "/steps/1/", 1, 120, Instant.now());

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.enqueueProvider(legacy)))
                    .isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("outputPrefix");
            assertThat(context.getBean(EventCapture.class).providerEvents).isEmpty();
        });
    }

    @Test
    void providerJobWhoseProfileIsMissingOrBelongsToAnotherCapabilityIsRefused() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            Map<String, Object> payload = Map.of("script", "Mua máy lọc nước ngay hôm nay");

            for (Map<String, Object> params : List.<Map<String, Object>>of(
                    Map.of("payload", payload),
                    Map.of("profile", "product_ad_v1", "payload", payload))) {
                assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.enqueueProvider(
                        providerJob("generate_speech", params))))
                        .as(params.toString())
                        .isInstanceOf(ConstraintViolationException.class)
                        .hasMessageContaining("profile");
            }
            assertThat(context.getBean(EventCapture.class).providerEvents).isEmpty();
        });
    }

    @Test
    void providerJobWithAnUnknownParamsKeyIsRefused() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.enqueueProvider(
                    providerJob("generate_speech", Map.of("profile", "vi_voice_v1",
                            "payload", Map.of("script", "Mua ngay"), "voice", "alloy")))))
                    .isInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("voice");
            assertThat(context.getBean(EventCapture.class).providerEvents).isEmpty();
        });
    }

    @Test
    void isEnabledMirrorsTheProperty() {
        runner.run(context -> assertThat(context.getBean(MediaJobs.class).isEnabled()).isTrue());
        runner.withPropertyValues("flowcortex.media.enabled=false")
                .run(context -> assertThat(context.getBean(MediaJobs.class).isEnabled()).isFalse());
    }

    @Test
    void acceptedOutputsAreRegisteredWithAttemptProvenance() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            MediaAssetRepository repository = context.getBean(MediaAssetRepository.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID ownerId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            UUID stepId = UUID.randomUUID();
            MediaOutput output = new MediaOutput("video", "flowcortex-media",
                    "runs/" + runId + "/steps/4/attempts/3/video.mp4", "video/mp4", 42L, null);

            tx.executeWithoutResult(status -> jobs.registerOutputs(ownerId, runId, stepId, 4, (short) 3,
                    List.of(output)));

            org.mockito.ArgumentCaptor<Iterable<MediaAsset>> saved = org.mockito.ArgumentCaptor.forClass(Iterable.class);
            verify(repository).saveAll(saved.capture());
            MediaAsset asset = saved.getValue().iterator().next();
            assertThat(asset.getOwnerId()).isEqualTo(ownerId);
            assertThat(asset.getSourceRunStepId()).isEqualTo(stepId);
            assertThat(asset.getSourceAttempt()).isEqualTo((short) 3);
            assertThat(asset.getObjectKey()).isEqualTo(output.objectKey());
        });
    }

    @Test
    void bindingCopiesEachUploadIntoTheMediaBucketAndBindsTheCopy() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            S3MediaStore store = context.getBean(S3MediaStore.class);
            MediaAssetBindingRepository bindings = context.getBean(MediaAssetBindingRepository.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID runId = UUID.randomUUID();
            List<MediaAssetInput> uploads = List.of(
                    new MediaAssetInput(UUID.randomUUID(), new MediaJobInput("image_1", "uploads", "a")),
                    new MediaAssetInput(UUID.randomUUID(), new MediaJobInput("image_2", "uploads", "b")));
            uploads.forEach(upload -> givenRegistered(context, upload.assetId(), "image"));
            when(store.readPrefix(eq("flowcortex-media"), anyString(), eq(16))).thenReturn(Optional.of(PNG_PREFIX));

            List<MediaAssetInput> copies = jobs.copyRunInputs(runId, uploads);
            tx.executeWithoutResult(status -> jobs.bindRunInputs(runId, copies));

            assertThat(copies).extracting(MediaAssetInput::input)
                    .containsExactly(
                            new MediaJobInput("image_1", "flowcortex-media",
                                    "runs/" + runId + "/inputs/image_1"),
                            new MediaJobInput("image_2", "flowcortex-media",
                                    "runs/" + runId + "/inputs/image_2"));
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(store, bindings);
            order.verify(store).copy("uploads", "a", "flowcortex-media", "runs/" + runId + "/inputs/image_1");
            order.verify(store).readPrefix("flowcortex-media", "runs/" + runId + "/inputs/image_1", 16);
            order.verify(store).copy("uploads", "b", "flowcortex-media", "runs/" + runId + "/inputs/image_2");
            order.verify(store).readPrefix("flowcortex-media", "runs/" + runId + "/inputs/image_2", 16);
            order.verify(bindings).saveAll(any());
            verify(store, never()).readPrefix(eq("uploads"), anyString(), anyInt());
        });
    }

    @Test
    void theCopyAndItsContentCheckRunWithNoTransactionOpenAndRefuseToJoinOne() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            S3MediaStore store = context.getBean(S3MediaStore.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID assetId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            givenRegistered(context, assetId, "video");
            List<Boolean> inTransaction = new ArrayList<>();
            org.mockito.Mockito.doAnswer(call -> inTransaction.add(
                    TransactionSynchronizationManager.isActualTransactionActive()))
                    .when(store).copy(anyString(), anyString(), anyString(), anyString());
            when(store.readPrefix(anyString(), anyString(), anyInt())).thenAnswer(call -> {
                inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
                return Optional.of(MP4_PREFIX);
            });
            List<MediaAssetInput> upload = List.of(
                    new MediaAssetInput(assetId, new MediaJobInput("media", "flowcortex-uploads", "o/u")));

            jobs.copyRunInputs(runId, upload);

            assertThat(inTransaction).containsExactly(false, false);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.copyRunInputs(runId, upload)))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verify(store, org.mockito.Mockito.times(1)).copy(anyString(), anyString(), anyString(), anyString());
        });
    }

    @Test
    void onlyACopyUnderTheRunsOwnInputsCanBeBoundAndOnlyInsideTheCallersTransaction() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            MediaAssetBindingRepository bindings = context.getBean(MediaAssetBindingRepository.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID runId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();
            List<MediaAssetInput> copy = List.of(new MediaAssetInput(assetId,
                    new MediaJobInput("media", "flowcortex-media", "runs/" + runId + "/inputs/media")));

            assertThatThrownBy(() -> jobs.bindRunInputs(runId, copy))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.bindRunInputs(runId, List.of(
                    new MediaAssetInput(assetId, new MediaJobInput("media", "flowcortex-uploads", "o/u"))))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.bindRunInputs(UUID.randomUUID(), copy)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(bindings, never()).saveAll(any());
        });
    }

    @Test
    void aSingleUploadIsCopiedUnderTheRunAndTheCopyIsWhatIsSniffedAndBound() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            S3MediaStore store = context.getBean(S3MediaStore.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID ownerId = UUID.randomUUID();
            UUID uploadId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            String copyKey = "runs/" + runId + "/inputs/media";
            when(store.head("flowcortex-uploads", ownerId + "/" + uploadId))
                    .thenReturn(Optional.of(new S3MediaStore.StoredObject(16L, "video/mp4")));
            when(store.readPrefix("flowcortex-media", copyKey, 16)).thenReturn(Optional.of(MP4_PREFIX));

            MediaAssetInput upload = jobs.resolveUpload(ownerId, uploadId);
            givenRegistered(context, uploadId, "video");
            List<MediaAssetInput> copies = jobs.copyRunInputs(runId, List.of(upload));
            tx.executeWithoutResult(status -> jobs.bindRunInputs(runId, copies));

            assertThat(copies).extracting(MediaAssetInput::input)
                    .containsExactly(new MediaJobInput("media", "flowcortex-media", copyKey));
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(store);
            order.verify(store).copy("flowcortex-uploads", ownerId + "/" + uploadId, "flowcortex-media", copyKey);
            order.verify(store).readPrefix("flowcortex-media", copyKey, 16);
            verify(store, never()).readPrefix(eq("flowcortex-uploads"), anyString(), anyInt());
            verify(context.getBean(MediaAssetBindingRepository.class)).saveAll(any());
        });
    }

    @Test
    void aCopyWhoseContentIsNotItsKindIsRefusedOnTheUploadFieldAndNothingIsBound() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            S3MediaStore store = context.getBean(S3MediaStore.class);
            UUID assetId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            givenRegistered(context, assetId, "video");
            when(store.readPrefix("flowcortex-media", "runs/" + runId + "/inputs/media", 16))
                    .thenReturn(Optional.of("my meeting notes".getBytes()));

            assertThatThrownBy(() -> jobs.copyRunInputs(runId, List.of(
                    new MediaAssetInput(assetId, new MediaJobInput("media", "flowcortex-uploads", "o/u")))))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).isEqualTo("uploadId");
                        assertThat(e.getMessage())
                                .isEqualTo("This file isn't a valid video file. Accepted: .mp4, .mov, .webm, .mkv.");
                    });
            verify(context.getBean(MediaAssetBindingRepository.class), never()).saveAll(any());
        });
    }

    @Test
    void aSingleUploadGoneBeforeItsCopyIsRefusedOnTheUploadField() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            S3MediaStore store = context.getBean(S3MediaStore.class);
            UUID runId = UUID.randomUUID();
            doThrow(new UploadRejectedException("uploadIds", "Upload the file again"))
                    .when(store).copy(anyString(), anyString(), anyString(), anyString());

            assertThatThrownBy(() -> jobs.copyRunInputs(runId, List.of(
                    new MediaAssetInput(UUID.randomUUID(), new MediaJobInput("media", "flowcortex-uploads", "o/u")))))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).isEqualTo("uploadId");
                        assertThat(e.getMessage()).isEqualTo("Upload the file again");
                    });
            assertThatThrownBy(() -> jobs.copyRunInputs(runId, List.of(
                    new MediaAssetInput(UUID.randomUUID(), new MediaJobInput("image_1", "flowcortex-uploads", "o/i")))))
                    .isInstanceOfSatisfying(UploadRejectedException.class,
                            e -> assertThat(e.field()).isEqualTo("uploadIds"));
        });
    }

    private static void givenRegistered(org.springframework.context.ApplicationContext context, UUID assetId,
            String kind) {
        when(context.getBean(MediaAssetRepository.class).findById(assetId)).thenReturn(Optional.of(new MediaAsset(
                assetId, UUID.randomUUID(), kind, "flowcortex-uploads", "o/" + assetId, kind + "/x", 16L, null,
                Instant.now())));
    }

    @Test
    void aFailedCopyStopsTheRunFromBindingAnything() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            S3MediaStore store = context.getBean(S3MediaStore.class);
            MediaAssetBindingRepository bindings = context.getBean(MediaAssetBindingRepository.class);
            UUID runId = UUID.randomUUID();
            doThrow(new IllegalStateException("store unreachable"))
                    .when(store).copy("uploads", "a", "flowcortex-media", "runs/" + runId + "/inputs/image_1");

            assertThatThrownBy(() -> jobs.copyRunInputs(runId, List.of(
                    new MediaAssetInput(UUID.randomUUID(), new MediaJobInput("image_1", "uploads", "a")))))
                    .isInstanceOf(IllegalStateException.class);
            verify(bindings, never()).saveAll(any());
        });
    }

    @Test
    void outputWrittenBeforeTheAttemptScopedPrefixLandedIsStillRegisteredOnAttemptOne() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            MediaAssetRepository repository = context.getBean(MediaAssetRepository.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID runId = UUID.randomUUID();
            UUID stepId = UUID.randomUUID();
            MediaOutput legacy = new MediaOutput("video", "flowcortex-media",
                    "runs/" + runId + "/steps/4/video.mp4", "video/mp4", 42L, null);

            tx.executeWithoutResult(status -> jobs.registerOutputs(UUID.randomUUID(), runId, stepId, 4, (short) 1,
                    List.of(legacy)));

            org.mockito.ArgumentCaptor<Iterable<MediaAsset>> saved = org.mockito.ArgumentCaptor.forClass(Iterable.class);
            verify(repository).saveAll(saved.capture());
            assertThat(saved.getValue().iterator().next().getObjectKey()).isEqualTo(legacy.objectKey());
        });
    }

    @Test
    void legacyPrefixIsRefusedOnAnyAttemptAfterTheFirst() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            UUID runId = UUID.randomUUID();
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.registerOutputs(UUID.randomUUID(),
                    runId, UUID.randomUUID(), 4, (short) 2,
                    List.of(new MediaOutput("video", "flowcortex-media",
                            "runs/" + runId + "/steps/4/video.mp4", "video/mp4", 42L, null)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Worker output is outside the current run prefix");
        });
    }

    @Test
    void outputOutsideTheCurrentRunPrefixIsRejected() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> jobs.registerOutputs(UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), 1, (short) 1,
                    List.of(new MediaOutput("video", "flowcortex-media", "runs/another/video.mp4",
                            "video/mp4", 42L, null)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Worker output is outside the current run prefix");
        });
    }

    @Test
    void limitsForProbeReturnTheConfiguredValues() {
        runner.withPropertyValues("flowcortex.media.queue-timeout=45m", "flowcortex.media.max-runtime.probe=7m")
                .run(context -> assertThat(context.getBean(MediaJobs.class).limitsFor("probe"))
                        .isEqualTo(new MediaLimits(Duration.ofMinutes(45), Duration.ofMinutes(7), 524_288_000L)));
    }

    @Test
    void limitsForExtractAudioReturnTheConfiguredValues() {
        runner.withPropertyValues("flowcortex.media.max-runtime.extract_audio=8m")
        .run(
            context -> assertThat(context.getBean(MediaJobs.class).limitsFor("extract_audio"))
                    .isEqualTo(new MediaLimits(Duration.ofMinutes(30), Duration.ofMinutes(8), 524_288_000L)));
    }

    @Test
    void limitsForAnUnknownCapabilityIsRefused() {
        runner.run(context -> assertThatThrownBy(() -> context.getBean(MediaJobs.class).limitsFor("transcode"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transcode"));
    }

    @Test
    void uploadExtensionsMapThroughTheCanonicalTable() {
        runner.run(context -> assertThat(context.getBean(MediaProperties.class).upload().mimeTypes())
                .containsExactly(
                        Map.entry("mp4", "video/mp4"),
                        Map.entry("mov", "video/quicktime"),
                        Map.entry("webm", "video/webm"),
                        Map.entry("mkv", "video/x-matroska"),
                        Map.entry("m4a", "audio/mp4"),
                        Map.entry("mp3", "audio/mpeg"),
                        Map.entry("wav", "audio/wav"),
                        Map.entry("jpg", "image/jpeg"),
                        Map.entry("jpeg", "image/jpeg"),
                        Map.entry("png", "image/png"),
                        Map.entry("webp", "image/webp")));
    }

    @Test
    void anUploadExtensionOutsideTheCanonicalTableFailsStartup() {
        runner.withPropertyValues("flowcortex.media.upload.extensions=mp4,exe")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("exe");
                });
    }

    // @Test
    // void aCapabilityWithoutAMaxRuntimeFailsStartup() {
    //     runner.withPropertyValues("flowcortex.media.max-runtime.probe=")
    //             .run(context -> assertThat(context).hasFailed());
    // }
    @Test
    void aCapabilityWithoutAMaxRuntimeFailsStartup() {
        runner.withPropertyValues("flowcortex.media.max-runtime.extract_audio=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("max-runtime.extract_audio");
                });
    }

    @Test
    void aValidationFailureLeavesTheCallersTransactionCommittable() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            assertThatCode(() -> tx.executeWithoutResult(status -> {
                assertThatThrownBy(() -> jobs.enqueue(job(null))).isInstanceOf(ConstraintViolationException.class);
                assertThat(status.isRollbackOnly()).isFalse();
            })).doesNotThrowAnyException();
            assertThat(context.getBean(EventCapture.class).events).isEmpty();
        });
    }

    @Test
    void aConsumerTimeoutShorterThanTheProviderCeilingFailsStartup() {
        runner.withPropertyValues(
                "flowcortex.media.max-runtime.probe=5m",
                "flowcortex.media.max-runtime.extract_audio=5m",
                "flowcortex.media.max-runtime.align_audio=5m",
                "flowcortex.media.max-runtime.render_video=5m",
                "flowcortex.media.provider-max-runtime=10m",
                "flowcortex.media.consumer-timeout=12m")
        .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("provider-max-runtime");
        });
    }

    @Test
    void aConsumerTimeoutShorterThanTheVideoCeilingFailsStartup() {
        runner.withPropertyValues(
                "flowcortex.media.provider-video-max-runtime=1081s",
                "flowcortex.media.consumer-timeout=20m")
        .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("provider-video-max-runtime");
        });
    }

    @Test
    void theProviderLimitsComeFromTheProviderPropertiesNotAMediaCapability() {
        runner.withPropertyValues(
                "flowcortex.media.provider-max-runtime=3m",
                "flowcortex.media.provider-queue-timeout=9m",
                "flowcortex.media.provider-video-max-runtime=11m",
                "flowcortex.media.provider-video-queue-timeout=17m")
        .run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            for (String capability : List.of("generate_script", "generate_storyboard", "generate_speech")) {
                MediaLimits limits = jobs.providerLimits(capability);
                assertThat(limits.maxRuntime()).as(capability).isEqualTo(Duration.ofMinutes(3));
                assertThat(limits.queueTimeout()).as(capability).isEqualTo(Duration.ofMinutes(9));
            }
            MediaLimits video = jobs.providerLimits("generate_video");
            assertThat(video.maxRuntime()).isEqualTo(Duration.ofMinutes(11));
            assertThat(video.queueTimeout()).isEqualTo(Duration.ofMinutes(17));
        });
    }

    @Test
    void aMissingProviderCeilingFailsStartupInsteadOfDefaulting() {
        withoutProperty("flowcortex.media.provider-max-runtime")
                .run(context -> assertThat(context).hasFailed());
        withoutProperty("flowcortex.media.provider-video-max-runtime")
                .run(context -> assertThat(context).hasFailed());
        withoutProperty("flowcortex.media.provider-video-queue-timeout")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aConsumerTimeoutThatDoesNotOutlastTheLongestRunPlusBackoffAndMarginFailsStartup() {
        runner.withPropertyValues(
                "flowcortex.media.max-runtime.probe=5m",
                "flowcortex.media.max-runtime.extract_audio=5m",
                "flowcortex.media.max-runtime.align_audio=5m",
                "flowcortex.media.max-runtime.render_video=5m",
                "flowcortex.media.consumer-timeout=7m")
        .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("consumer-timeout");
        });

        runner.withPropertyValues(
                "flowcortex.media.max-runtime.probe=5m",
                "flowcortex.media.max-runtime.extract_audio=5m",
                "flowcortex.media.max-runtime.align_audio=5m",
                "flowcortex.media.max-runtime.render_video=5m",
                "flowcortex.media.provider-video-max-runtime=5m",
                "flowcortex.media.consumer-timeout=421s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aMissingEnabledOrPathStyleFlagFailsStartupInsteadOfDefaultingToFalse() {
        withoutProperty("flowcortex.media.enabled").run(context -> assertThat(context).hasFailed());
        withoutProperty("flowcortex.media.s3.path-style").run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner withoutProperty(String key) {
        String[] kept = Arrays.stream(VALID_PROPERTIES).filter(p -> !p.startsWith(key + "=")).toArray(String[]::new);
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
                .withUserConfiguration(TestConfig.class, MediaConfig.class, MediaJobsImpl.class)
                .withPropertyValues(kept);
    }

    private static S3MediaStore.PresignedPut signed() {
        return new S3MediaStore.PresignedPut("http://localhost:9000/flowcortex-uploads/o/u?X-Amz-Signature=abc",
                Map.of("Content-Type", "video/quicktime"), Instant.parse("2026-09-30T10:15:00Z"));
    }

    @Test
    void anUploadGrantSignsAServerChosenKeyForTheCanonicalTypeOfTheExtension(CapturedOutput output) {
        runner.run(context -> {
            S3MediaStore store = context.getBean(S3MediaStore.class);
            when(store.presignPut(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(signed());
            UUID ownerId = UUID.randomUUID();

            UploadGrant grant = context.getBean(MediaJobs.class).prepareUpload(ownerId, "IMG_0412.MOV", 16L);

            verify(store).presignPut("flowcortex-uploads", ownerId + "/" + grant.uploadId(), "video/quicktime", 16L,
                    Duration.ofMinutes(15));
            assertThat(grant.method()).isEqualTo("PUT");
            assertThat(grant.url()).isEqualTo(signed().url());
            assertThat(grant.headers()).containsExactly(Map.entry("Content-Type", "video/quicktime"));
            assertThat(grant.expiresAt()).isEqualTo(signed().expiresAt());
            assertThat(grant.maxBytes()).isEqualTo(524_288_000L);
            assertThat(output.getAll()).contains("flowcortex-uploads", ownerId + "/" + grant.uploadId(), "16")
                    .doesNotContain("X-Amz-", "IMG_0412");
            assertThat(grant.toString()).contains(grant.uploadId().toString()).doesNotContain("X-Amz-", "localhost:9000");
        });
    }

    @Test
    void aDownloadIsSignedAsAnAttachmentNamedAfterTheOutputAndLoggedWithoutItsUrl(CapturedOutput output) {
        runner.run(context -> {
            S3MediaStore store = context.getBean(S3MediaStore.class);
            S3MediaStore.PresignedGet signed = new S3MediaStore.PresignedGet(
                    "http://localhost:9000/flowcortex-media/runs/r/steps/1/video-details.json?X-Amz-Signature=abc",
                    Instant.parse("2026-09-30T10:15:00Z"));
            when(store.presignGet(anyString(), anyString(), anyString(), any())).thenReturn(signed);

            MediaDownload download = context.getBean(MediaJobs.class)
                    .presignedGet("flowcortex-media", "runs/r/steps/1/video-details.json", "video-details.json");

            verify(store).presignGet("flowcortex-media", "runs/r/steps/1/video-details.json",
                    "attachment; filename=\"video-details.json\"", Duration.ofMinutes(15));
            assertThat(download.url()).isEqualTo(signed.url());
            assertThat(download.expiresAt()).isEqualTo(signed.expiresAt());
            assertThat(output.getAll()).contains("flowcortex-media", "runs/r/steps/1/video-details.json")
                    .doesNotContain("X-Amz-");
            assertThat(download.toString()).doesNotContain("X-Amz-", "localhost:9000");
        });
    }

    @Test
    void aDownloadOutsideTheMediaBucketOrUnderANameThatIsNotPlainIsRefusedAndNothingIsSigned() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);

            assertThatThrownBy(() -> jobs.presignedGet("flowcortex-uploads", "u/up", "video-details.json"))
                    .isInstanceOf(IllegalArgumentException.class);
            for (String name : List.of("a\"b.json", "a\r\nSet-Cookie: x", "../x.json", "")) {
                assertThatThrownBy(() -> jobs.presignedGet("flowcortex-media", "runs/r/steps/1/x", name))
                        .as(name).isInstanceOf(IllegalArgumentException.class);
            }
            verify(context.getBean(S3MediaStore.class), never()).presignGet(any(), any(), any(), any());
        });
    }

    @Test
    void anUploadOfNoBytesOrMoreThanTheMaximumIsRefusedWithTheSizeSentence() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            for (long size : new long[] {0L, 524_288_001L}) {
                assertThatThrownBy(() -> jobs.prepareUpload(UUID.randomUUID(), "clip.mp4", size))
                        .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                            assertThat(e.field()).isEqualTo("size");
                            assertThat(e.getMessage()).isEqualTo(SIZE_SENTENCE);
                        });
            }
            verify(context.getBean(S3MediaStore.class), never()).presignPut(any(), any(), any(), anyLong(), any());
        });
    }

    @Test
    void eachKindIsCappedAtItsOwnSizeAndExactlyTheCapIsGranted() {
        runner.run(context -> {
            S3MediaStore store = context.getBean(S3MediaStore.class);
            when(store.presignPut(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(signed());
            MediaJobs jobs = context.getBean(MediaJobs.class);

            assertThat(jobs.prepareUpload(UUID.randomUUID(), "voice.wav", 104_857_600L).maxBytes())
                    .isEqualTo(104_857_600L);
            assertThat(jobs.prepareUpload(UUID.randomUUID(), "photo.png", 10_485_760L).maxBytes())
                    .isEqualTo(10_485_760L);
            assertThatThrownBy(() -> jobs.prepareUpload(UUID.randomUUID(), "voice.wav", 104_857_601L))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).isEqualTo("size");
                        assertThat(e.getMessage()).isEqualTo(AUDIO_SIZE_SENTENCE);
                    });
            assertThatThrownBy(() -> jobs.prepareUpload(UUID.randomUUID(), "photo.png", 10_485_761L))
                    .isInstanceOfSatisfying(UploadRejectedException.class,
                            e -> assertThat(e.getMessage()).isEqualTo(IMAGE_SIZE_SENTENCE));
        });
    }

    @Test
    void anUploadOutsideTheAllowlistIsRefusedWithTheTypeSentence() {
        runner.run(context -> {
            MediaJobs jobs = context.getBean(MediaJobs.class);
            for (String fileName : new String[] {"notes.txt", "no-extension", "archive.mp4.exe"}) {
                assertThatThrownBy(() -> jobs.prepareUpload(UUID.randomUUID(), fileName, 16L))
                        .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                            assertThat(e.field()).isEqualTo("fileName");
                            assertThat(e.getMessage()).isEqualTo(TYPE_SENTENCE);
                        });
            }
        });
    }

    @Test
    void aStoredUploadResolvesToTheObjectAtTheCallersKeyWithoutReadingItsContent() {
        runner.run(context -> {
            UUID ownerId = UUID.randomUUID();
            UUID uploadId = UUID.randomUUID();
            S3MediaStore store = context.getBean(S3MediaStore.class);
            when(store.head("flowcortex-uploads", ownerId + "/" + uploadId))
                    .thenReturn(Optional.of(new S3MediaStore.StoredObject(16L, "video/mp4")));

            assertThat(context.getBean(MediaJobs.class).resolveUpload(ownerId, uploadId))
                    .isEqualTo(new MediaAssetInput(uploadId,
                            new MediaJobInput("media", "flowcortex-uploads", ownerId + "/" + uploadId)));
            verify(store, never()).readPrefix(anyString(), anyString(), anyInt());
        });
    }

    @Test
    void withTheContentCheckOffTheCopyIsMadeWithoutReadingItsFirstBytes() {
        runner.withPropertyValues("flowcortex.media.upload.content-check.enabled=false").run(context -> {
            S3MediaStore store = context.getBean(S3MediaStore.class);
            UUID runId = UUID.randomUUID();

            List<MediaAssetInput> copies = context.getBean(MediaJobs.class).copyRunInputs(runId,
                    List.of(new MediaAssetInput(UUID.randomUUID(),
                            new MediaJobInput("media", "flowcortex-uploads", "o/u"))));

            assertThat(copies).singleElement().extracting(input -> input.input().objectKey())
                    .isEqualTo("runs/" + runId + "/inputs/media");
            verify(store, never()).readPrefix(anyString(), anyString(), anyInt());
            verify(context.getBean(MediaAssetRepository.class), never()).findById(any());
        });
    }

    @Test
    void imageUploadsResolveInRequestOrderWithStableBindingKeys() {
        runner.run(context -> {
            UUID ownerId = UUID.randomUUID();
            List<UUID> uploadIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            when(context.getBean(S3MediaStore.class).head(eq("flowcortex-uploads"), anyString()))
                    .thenReturn(Optional.of(new S3MediaStore.StoredObject(16L, "image/png", "etag")));

            List<MediaAssetInput> inputs = context.getBean(MediaJobs.class).resolveImageUploads(ownerId, uploadIds);

            assertThat(inputs).extracting(MediaAssetInput::assetId).containsExactlyElementsOf(uploadIds);
            assertThat(inputs).extracting(input -> input.input().key())
                    .containsExactly("image_1", "image_2", "image_3");
        });
    }

    @Test
    void duplicateOrTooManyImageUploadsAreRejectedBeforeObjectStoreAccess() {
        runner.run(context -> {
            UUID repeated = UUID.randomUUID();
            MediaJobs jobs = context.getBean(MediaJobs.class);

            assertThatThrownBy(() -> jobs.resolveImageUploads(UUID.randomUUID(), List.of(repeated, repeated)))
                    .isInstanceOf(UploadRejectedException.class);
            assertThatThrownBy(() -> jobs.resolveImageUploads(UUID.randomUUID(),
                    java.util.stream.IntStream.range(0, 11).mapToObj(ignored -> UUID.randomUUID()).toList()))
                    .isInstanceOf(UploadRejectedException.class);
            verify(context.getBean(S3MediaStore.class), never()).head(anyString(), anyString());
        });
    }

    @Test
    void aProductImageLargerThanTenMegabytesIsRejectedWithoutRegistration() {
        runner.run(context -> {
            when(context.getBean(S3MediaStore.class).head(anyString(), anyString()))
                    .thenReturn(Optional.of(new S3MediaStore.StoredObject(10L * 1024 * 1024 + 1, "image/png")));

            assertThatThrownBy(() -> context.getBean(MediaJobs.class)
                    .resolveImageUploads(UUID.randomUUID(), List.of(UUID.randomUUID())))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).isEqualTo("uploadId");
                        assertThat(e.getMessage()).isEqualTo(IMAGE_SIZE_SENTENCE);
                    });
            verify(context.getBean(MediaAssetRepository.class), never()).save(any());
        });
    }

    @Test
    void aMissingUploadIsRefusedWithUploadTheFileAgain() {
        runner.run(context -> {
            when(context.getBean(S3MediaStore.class).head(anyString(), anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> context.getBean(MediaJobs.class).resolveUpload(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).isEqualTo("uploadId");
                        assertThat(e.getMessage()).isEqualTo("Upload the file again");
                    });
        });
    }

    @Test
    void anAssetAlreadyRegisteredToAnotherOwnerIsHiddenAsMissing() {
        runner.run(context -> {
            UUID uploadId = UUID.randomUUID();
            UUID firstOwner = UUID.randomUUID();
            MediaAsset existing = new MediaAsset(uploadId, firstOwner, "image", "flowcortex-uploads",
                    firstOwner + "/" + uploadId, "image/png", 16L, "etag", Instant.now());
            when(context.getBean(MediaAssetRepository.class).findById(uploadId)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> context.getBean(MediaJobs.class)
                    .resolveImageUploads(UUID.randomUUID(), List.of(uploadId)))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).isEqualTo("uploadId");
                        assertThat(e.getMessage()).isEqualTo("Upload the file again");
                    });
            verify(context.getBean(S3MediaStore.class), never()).head(anyString(), anyString());
        });
    }

    @Test
    void aStoredObjectOverTheMaximumOrOfAnotherTypeIsRefusedNamingTheLimit() {
        runner.run(context -> {
            S3MediaStore store = context.getBean(S3MediaStore.class);
            MediaJobs jobs = context.getBean(MediaJobs.class);
            when(store.head(anyString(), anyString()))
                    .thenReturn(Optional.of(new S3MediaStore.StoredObject(524_288_001L, "video/mp4")))
                    .thenReturn(Optional.of(new S3MediaStore.StoredObject(16L, "text/plain")));

            assertThatThrownBy(() -> jobs.resolveUpload(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).isEqualTo("uploadId");
                        assertThat(e.getMessage()).isEqualTo(SIZE_SENTENCE);
                    });
            assertThatThrownBy(() -> jobs.resolveUpload(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).isEqualTo("uploadId");
                        assertThat(e.getMessage()).isEqualTo(MEDIA_TYPE_SENTENCE);
                    });
            verify(store, org.mockito.Mockito.times(2)).head(eq("flowcortex-uploads"), anyString());
        });
    }

    @Test
    void anImageIsRefusedAsTheInputOfAMediaCapabilityButStillAcceptedAsAProductImage() {
        runner.run(context -> {
            S3MediaStore store = context.getBean(S3MediaStore.class);
            MediaJobs jobs = context.getBean(MediaJobs.class);
            when(store.head(anyString(), anyString()))
                    .thenReturn(Optional.of(new S3MediaStore.StoredObject(16L, "image/jpeg")))
                    .thenReturn(Optional.of(new S3MediaStore.StoredObject(16L, "image/png")));

            assertThatThrownBy(() -> jobs.resolveUpload(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).isEqualTo("uploadId");
                        assertThat(e.getMessage()).isEqualTo(MEDIA_TYPE_SENTENCE);
                    });
            assertThat(jobs.resolveImageUploads(UUID.randomUUID(), List.of(UUID.randomUUID()))).hasSize(1);
            verify(context.getBean(MediaAssetRepository.class), org.mockito.Mockito.times(1)).save(any());
        });
    }

    @Test
    void s3CredentialsNeverAppearInToString() {
        runner.run(context -> assertThat(context.getBean(MediaProperties.class).toString())
                .doesNotContain("backend-secret-value")
                .doesNotContain("backend-key"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoResourceTransactionManager();
        }

        @Bean
        EventCapture eventCapture() {
            return new EventCapture();
        }

        @Bean
        S3MediaStore mediaStore() {
            return mock(S3MediaStore.class);
        }

        @Bean
        MediaAssetRepository mediaAssetRepository() {
            return mock(MediaAssetRepository.class);
        }

        @Bean
        MediaAssetBindingRepository mediaAssetBindingRepository() {
            return mock(MediaAssetBindingRepository.class);
        }
    }

    static class EventCapture {

        final List<MediaJobRequested> events = new ArrayList<>();
        final List<Boolean> publishedInTransaction = new ArrayList<>();
        final List<ProviderJobRequested> providerEvents = new ArrayList<>();

        @EventListener
        void on(MediaJobRequested event) {
            events.add(event);
            publishedInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        }

        @EventListener
        void on(ProviderJobRequested event) {
            providerEvents.add(event);
        }
    }

    /**
     * Opens real transaction boundaries without a database behind them. A participant's failure
     * marks the shared transaction rollback-only, so committing the outer one then throws
     * {@code UnexpectedRollbackException}, as it would with JPA.
     */
    static class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

        static final class Tx implements SmartTransactionObject {

            private boolean rollbackOnly;

            @Override
            public boolean isRollbackOnly() {
                return rollbackOnly;
            }

            @Override
            public void flush() {
                // nothing buffered
            }
        }

        @Override
        protected Object doGetTransaction() {
            Object bound = TransactionSynchronizationManager.getResource(this);
            return bound != null ? bound : new Tx();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.hasResource(this);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            TransactionSynchronizationManager.bindResource(this, transaction);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no resource to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no resource to roll back
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((Tx) status.getTransaction()).rollbackOnly = true;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            TransactionSynchronizationManager.unbindResourceIfPossible(this);
        }
    }
}
