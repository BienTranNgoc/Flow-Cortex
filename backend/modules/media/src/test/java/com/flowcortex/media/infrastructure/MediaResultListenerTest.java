package com.flowcortex.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flowcortex.media.api.MediaJobCompleted;
import com.flowcortex.media.api.MediaJobProgressed;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaProgress;
import com.flowcortex.media.api.MediaResult;
import com.flowcortex.media.application.MediaInbound;
import com.flowcortex.media.application.MediaProperties;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The inbound listeners over the real {@link MediaInbound} and Bean Validation: a body that is not
 * exactly a valid contract message is rejected without requeue (so it lands on the queue's DLQ)
 * and never published; a valid one is published once.
 */
@ExtendWith(OutputCaptureExtension.class)
class MediaResultListenerTest {

    private static final MediaProperties PROPERTIES = new MediaProperties(true, Duration.ofMinutes(30),
            Map.of(
                "probe", Duration.ofMinutes(5),
                "extract_audio", Duration.ofMinutes(10),
                "align_audio", Duration.ofMinutes(10),
                "render_video", Duration.ofMinutes(10)),
            Duration.ofMinutes(20),
            Duration.ofMinutes(2), Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            new MediaProperties.Upload(524_288_000L, List.of("mp4"), List.of("jpg"), Duration.ofMinutes(15)),
            new MediaProperties.S3("http://minio:9000", "http://localhost:9000", "us-east-1", true,
                    "flowcortex-uploads", "flowcortex-media", "backend-key", "backend-secret-value",
                    Duration.ofSeconds(5), Duration.ofSeconds(2)));

    private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final MediaInbound inbound = new MediaInbound(validation.getValidator(), events, PROPERTIES);
    private final MediaResultListener results = new MediaResultListener(inbound);
    private final MediaProgressListener progress = new MediaProgressListener(inbound);

    private final UUID jobId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @AfterEach
    void closeValidation() {
        validation.close();
    }

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private String completed(String extra) {
        return completed("flowcortex-media", "runs/" + runId + "/steps/1/video-details.json", extra);
    }

    private String completed(String bucket, String objectKey, String extra) {
        return "{\"schemaVersion\":\"1\",\"jobId\":\"" + jobId + "\",\"runId\":\"" + runId + "\",\"attempt\":1,"
                + "\"deliveryCount\":0,\"status\":\"completed\",\"outputs\":[{\"name\":\"video-details.json\","
                + "\"bucket\":\"" + bucket + "\",\"objectKey\":\"" + objectKey + "\","
                + "\"mime\":\"application/json\",\"bytes\":42}],\"data\":{\"durationMs\":1000},"
                + "\"finishedAt\":\"2026-09-30T10:00:00Z\",\"workerId\":\"worker-1\"" + extra + "}";
    }

    @Test
    void anOutputStoredOutsideItsRunIsRejectedToTheDeadLetterQueueAndNamesTheJob(CapturedOutput output) {
        UUID otherRun = UUID.randomUUID();
        List<String> foreignKeys = List.of(
                "runs/" + otherRun + "/steps/1/video-details.json",
                "runs/" + runId + "/steps/1/../../" + otherRun + "/steps/1/video-details.json",
                "runs/" + runId + "/steps/0/video-details.json",
                "runs/" + runId + "/steps/1/",
                runId + "/video-details.json");

        for (String key : foreignKeys) {
            assertThatThrownBy(() -> results.onResult(message(completed("flowcortex-media", key, ""))))
                    .as(key)
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }

        verify(events, never()).publishEvent(any());
        assertThat(output.getAll()).contains("WARN", jobId.toString(), "is not stored under runs/" + runId + "/steps/");
    }

    @Test
    void anOutputInAnotherBucketIsRejected(CapturedOutput output) {
        String key = "runs/" + runId + "/steps/1/video-details.json";

        assertThatThrownBy(() -> results.onResult(message(completed("flowcortex-uploads", key, ""))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(events, never()).publishEvent(any());
        assertThat(output.getAll()).contains("WARN", jobId.toString(), "flowcortex-uploads");
    }

    @Test
    void aValidResultIsPublishedOnce() {
        results.onResult(message(completed("")));

        ArgumentCaptor<MediaJobCompleted> event = ArgumentCaptor.forClass(MediaJobCompleted.class);
        verify(events).publishEvent(event.capture());
        MediaResult result = event.getValue().result();
        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.status()).isEqualTo(MediaResult.Status.COMPLETED);
        assertThat(result.outputs()).extracting(MediaOutput::name).containsExactly("video-details.json");
        assertThat(result.data()).containsEntry("durationMs", 1000);
    }

    @Test
    void aMalformedResultIsRejectedToTheDeadLetterQueueAndNamesTheJob(CapturedOutput output) {
        assertThatThrownBy(() -> results.onResult(message("{\"jobId\":\"" + jobId + "\",\"status\":\"done\"}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(events, never()).publishEvent(any());
        assertThat(output.getAll()).contains("WARN", jobId.toString());
    }

    @Test
    void aResultWithAnUnknownFieldIsRejected() {
        assertThatThrownBy(() -> results.onResult(message(completed(",\"surprise\":true"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void aCompletedResultCarryingAnErrorBlockBreaksTheContractAndIsRejected(CapturedOutput output) {
        String withError = completed(",\"error\":{\"code\":\"probe_failed\",\"message\":\"x\",\"retryable\":false}");

        assertThatThrownBy(() -> results.onResult(message(withError)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(events, never()).publishEvent(any());
        assertThat(output.getAll()).contains("WARN", jobId.toString(), "error must be present exactly when status is error");
    }

    @Test
    void aBodyThatIsNotJsonIsRejectedWithoutAJobId(CapturedOutput output) {
        assertThatThrownBy(() -> results.onResult(message("not json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(events, never()).publishEvent(any());
        assertThat(output.getAll()).contains("WARN");
    }

    @Test
    void aValidProgressMessageIsPublishedAndAnOutOfRangePercentIsRejected() {
        String started = "{\"schemaVersion\":\"1\",\"jobId\":\"" + jobId + "\",\"runId\":\"" + runId + "\","
                + "\"attempt\":1,\"deliveryCount\":0,\"kind\":\"heartbeat\",\"percent\":%d,"
                + "\"at\":\"2026-09-30T10:00:00Z\"}";

        progress.onProgress(message(started.formatted(40)));
        assertThatThrownBy(() -> progress.onProgress(message(started.formatted(101))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        ArgumentCaptor<MediaJobProgressed> event = ArgumentCaptor.forClass(MediaJobProgressed.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().progress().kind()).isEqualTo(MediaProgress.Kind.HEARTBEAT);
        assertThat(event.getValue().progress().percent()).isEqualTo(40);
    }
}
