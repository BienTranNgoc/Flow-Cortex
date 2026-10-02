package com.flowcortex.app.observability;

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
import org.junit.jupiter.api.Test;
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

/**
 * A probe run started over HTTP: the response names its trace, and the job the backend sends the
 * worker continues that trace and carries the run id as baggage.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TracePropagationIT extends AbstractWorkflowHttpIT {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private RabbitTemplate rabbit;

    record StartUploadRunView(UUID uploadId, String originalName) {
    }

    record StepView(UUID id) {
    }

    record StartedRunView(UUID id, List<StepView> steps) {
    }

    @Test
    void theStartRunResponseNamesTheTraceThatTheJobMessageContinuesWithTheRunIdAsBaggage() {
        UserView user = newUser("trace-propagation");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("Inspect the file", "probe", null));
        UUID uploadId = UUID.randomUUID();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(user.id() + "/" + uploadId).contentType("video/mp4"),
                    RequestBody.fromBytes("a one-second clip".getBytes(StandardCharsets.UTF_8)));
        }

        ResponseEntity<StartedRunView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", loginAs(user),
                new StartUploadRunView(uploadId, "clip.mp4"), StartedRunView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(started.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        String traceId = started.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));

        Message job = receiveJob(started.getBody().steps().getFirst().id());
        Object traceparent = job.getMessageProperties().getHeaders().get("traceparent");
        // Flags: sampled (01), plus the W3C level-2 random-trace-id bit (02) the SDK may set.
        assertThat(traceparent).asString().matches("00-" + traceId + "-[0-9a-f]{16}-0[13]");
        Object baggage = job.getMessageProperties().getHeaders().get("baggage");
        assertThat(baggage).asString().contains("runId=" + started.getBody().id());
    }

    @Test
    void everyResponseCarriesItsTraceIdNextToTheRequestId() {
        ResponseEntity<String> response = get("/api/v1/health", null, String.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).matches("[0-9a-f]{32}");
    }

    private Message receiveJob(UUID stepId) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive("media.jobs", 1_000);
            if (message != null && stepId.toString().equals(message.getMessageProperties().getMessageId())) {
                return message;
            }
        }
        throw new AssertionError("No job for step " + stepId + " reached media.jobs within " + WAIT);
    }
}
