package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.flowcortex.media.api.MediaJobProgressed;
import com.flowcortex.media.api.MediaProgress;
import com.flowcortex.media.api.MediaVendorTaskReported;
import com.flowcortex.media.api.VendorTaskReport;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** Progress routing: a vendor-task report must not reach the heartbeat listeners, and the reverse. */
class MediaInboundTest {

    private static final MediaProperties PROPERTIES = new MediaProperties(true, Duration.ofMinutes(30),
            Map.of(
                "probe", Duration.ofMinutes(5),
                "extract_audio", Duration.ofMinutes(10),
                "align_audio", Duration.ofMinutes(10),
                "render_video", Duration.ofMinutes(10)),
            Duration.ofMinutes(20),
            Duration.ofMinutes(2), Duration.ofMinutes(5),
            Duration.ofMinutes(10), Duration.ofMinutes(15),
            Duration.ofMinutes(15),
            new MediaProperties.Upload(524_288_000L, 104_857_600L, 10_485_760L, List.of("mp4"), List.of("jpg"),
                    Duration.ofMinutes(15), new MediaProperties.Upload.ContentCheck(true)),
            new MediaProperties.S3("http://minio:9000", "http://localhost:9000", "us-east-1", true,
                    "flowcortex-uploads", "flowcortex-media", "backend-key", "backend-secret-value",
                    Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMinutes(2)));

    private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final MediaInbound inbound = new MediaInbound(validation.getValidator(), events, PROPERTIES);

    private final UUID jobId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @AfterEach
    void closeValidation() {
        validation.close();
    }

    private MediaProgress progress(MediaProgress.Kind kind, VendorTaskReport report) {
        return new MediaProgress("1", jobId, runId, 1, 0, kind, null, null, Instant.parse("2026-11-09T10:00:00Z"),
                report);
    }

    private static VendorTaskReport newSubmit() {
        return new VendorTaskReport(null, "seedance", "seedance-1", "a".repeat(64), "b".repeat(64), "job-1", Map.of(),
                VendorTaskReport.Status.RUNNING, Instant.parse("2026-11-09T10:00:00Z"));
    }

    @Test
    void aVendorTaskReportPublishesExactlyOneVendorTaskEventAndNoProgressEvent() {
        VendorTaskReport report = newSubmit();

        inbound.acceptProgress(progress(MediaProgress.Kind.VENDOR_TASK, report));

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getAllValues()).singleElement().isEqualTo(new MediaVendorTaskReported(report, runId,
                jobId));
    }

    @Test
    void aHeartbeatPublishesOnlyTheProgressEvent() {
        MediaProgress heartbeat = progress(MediaProgress.Kind.HEARTBEAT, null);

        inbound.acceptProgress(heartbeat);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getAllValues()).singleElement().isEqualTo(new MediaJobProgressed(heartbeat));
    }

    @Test
    void aVendorTaskKindWithoutItsBlockIsRefusedAndNothingIsPublished() {
        assertThatThrownBy(() -> inbound.acceptProgress(progress(MediaProgress.Kind.VENDOR_TASK, null)))
                .isInstanceOf(ConstraintViolationException.class);

        verifyNoInteractions(events);
    }
}
