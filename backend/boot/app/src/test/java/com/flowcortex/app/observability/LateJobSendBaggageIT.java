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
 * The outbox sends a job from its own task on the async executor, which may start only after the
 * run that dispatched the job has moved on. One executor thread makes that order certain: the
 * send task queues behind the run-start listener and starts once the listener has returned.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.task.execution.pool.core-size=1",
    "spring.task.execution.pool.max-size=1"
})
@AutoConfigureTestRestTemplate
class LateJobSendBaggageIT extends AbstractWorkflowHttpIT {

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
    void aJobSentAfterItsRunMovedOnContinuesTheTraceWithTheRunIdAsBaggage() {
        UserView user = newUser("late-job-send");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("Inspect the file", "probe", null));
        UUID uploadId = UUID.randomUUID();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(user.id() + "/" + uploadId).contentType("video/mp4"),
                    RequestBody.fromBytes("a one-second clip".getBytes(StandardCharsets.UTF_8)));
        }

        ResponseEntity<StartedRunView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", loginAs(user),
                new StartUploadRunView(uploadId, "clip.mp4"), StartedRunView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        String traceId = started.getHeaders().getFirst("X-Trace-Id");
        Message job = receiveJob(started.getBody().steps().getFirst().id());
        Object traceparent = job.getMessageProperties().getHeaders().get("traceparent");
        assertThat(traceparent).asString().startsWith("00-" + traceId + "-");
        Object baggage = job.getMessageProperties().getHeaders().get("baggage");
        assertThat(baggage).asString().contains("runId=" + started.getBody().id());
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
