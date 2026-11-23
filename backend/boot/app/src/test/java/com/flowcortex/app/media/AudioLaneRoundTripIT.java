package com.flowcortex.app.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A run whose only step separates audio publishes its job on the audio lane through the real broker:
 * the job carries the traceable ids and reaches {@code media.audio.jobs}, and nothing reaches the
 * default {@code media.jobs} queue.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.workflow.reaper-interval-ms=3600000")
@AutoConfigureTestRestTemplate
class AudioLaneRoundTripIT extends AbstractWorkflowHttpIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin amqpAdmin;

    record StartUploadRunView(UUID uploadId, String originalName) {
    }

    record StepView(UUID id, int stepOrder, String status) {
    }

    record StartedRunView(UUID id, String status, List<StepView> steps) {
    }

    @BeforeEach
    void emptyTheQueuesThisTestReads() {
        for (String queue : List.of("media.jobs", "media.audio.jobs")) {
            amqpAdmin.purgeQueue(queue, false);
        }
    }

    @Test
    void aSeparationJobIsPublishedToTheAudioQueueAndNotToTheDefaultOne() {
        UserView user = newUser("audio-lane");
        WorkflowView workflow = newWorkflow(user.id(), false,
                new NewWorkflowStep("Separate the music", "separate_audio", null));
        UUID uploadId = UUID.randomUUID();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(user.id() + "/" + uploadId).contentType("audio/mpeg"),
                    RequestBody.fromBytes("a short clip".getBytes(StandardCharsets.UTF_8)));
        }

        ResponseEntity<StartedRunView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", loginAs(user),
                new StartUploadRunView(uploadId, "song.mp3"), StartedRunView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID stepId = started.getBody().steps().getFirst().id();
        Message job = receiveFor("media.audio.jobs", stepId);
        assertThat(job.getMessageProperties().getCorrelationId()).isEqualTo(started.getBody().id().toString());
        JsonNode body = JSON.readTree(job.getBody());
        assertThat(body.get("capability").asString()).isEqualTo("separate_audio");
        Message stray = rabbit.receive("media.jobs", 1_000);
        assertThat(stray == null ? null : stray.getMessageProperties().getMessageId()).isNotEqualTo(stepId.toString());
    }

    private Message receiveFor(String queue, UUID stepId) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive(queue, 1_000);
            if (message != null && stepId.toString().equals(message.getMessageProperties().getMessageId())) {
                return message;
            }
        }
        throw new AssertionError("No job for step " + stepId + " reached " + queue + " within " + WAIT);
    }
}
