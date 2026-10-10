package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.media.infrastructure.S3MediaStore;
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

    private static final String SIZE_SENTENCE = "Files must be between 1 byte and 500 MB.";
    private static final String TYPE_SENTENCE =
            "This file type can't be checked yet. Use .mp4, .mov, .webm, .mkv, .m4a, .mp3 or .wav.";

    private static final String[] VALID_PROPERTIES = {
        "flowcortex.media.enabled=true",
        "flowcortex.media.queue-timeout=30m",
        "flowcortex.media.max-runtime.probe=5m",
        "flowcortex.media.max-runtime.extract_audio=10m",
        "flowcortex.media.max-runtime.render_video=10m",
        "flowcortex.media.consumer-timeout=20m",
        "flowcortex.media.presign-ttl=15m",
        "flowcortex.media.upload.max-bytes=524288000",
        "flowcortex.media.upload.extensions=mp4,MOV,webm,mkv,m4a,mp3,wav",
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

    @Test
    void isEnabledMirrorsTheProperty() {
        runner.run(context -> assertThat(context.getBean(MediaJobs.class).isEnabled()).isTrue());
        runner.withPropertyValues("flowcortex.media.enabled=false")
                .run(context -> assertThat(context.getBean(MediaJobs.class).isEnabled()).isFalse());
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
                        Map.entry("wav", "audio/wav")));
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
    void aConsumerTimeoutThatDoesNotOutlastTheLongestRunPlusBackoffAndMarginFailsStartup() {
        runner.withPropertyValues(
        "flowcortex.media.max-runtime.probe=5m",
        "flowcortex.media.max-runtime.extract_audio=5m",
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
                "flowcortex.media.max-runtime.render_video=5m",
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
    void aStoredUploadResolvesToTheObjectAtTheCallersKey() {
        runner.run(context -> {
            UUID ownerId = UUID.randomUUID();
            UUID uploadId = UUID.randomUUID();
            when(context.getBean(S3MediaStore.class).head("flowcortex-uploads", ownerId + "/" + uploadId))
                    .thenReturn(Optional.of(new S3MediaStore.StoredObject(16L, "video/mp4")));

            assertThat(context.getBean(MediaJobs.class).resolveUpload(ownerId, uploadId))
                    .isEqualTo(new MediaJobInput("media", "flowcortex-uploads", ownerId + "/" + uploadId));
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
                        assertThat(e.getMessage()).isEqualTo(TYPE_SENTENCE);
                    });
            verify(store, org.mockito.Mockito.times(2)).head(eq("flowcortex-uploads"), anyString());
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
    }

    static class EventCapture {

        final List<MediaJobRequested> events = new ArrayList<>();
        final List<Boolean> publishedInTransaction = new ArrayList<>();

        @EventListener
        void on(MediaJobRequested event) {
            events.add(event);
            publishedInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
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
