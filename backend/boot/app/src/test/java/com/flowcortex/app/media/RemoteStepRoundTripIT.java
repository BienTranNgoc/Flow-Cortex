package com.flowcortex.app.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.application.StoringStepTestConfig;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One remote step through the real broker and object store, with this test as the worker: the
 * job leaves through the outbox as the bare contract body, and what the worker sends back is
 * applied by the workflow module's transition, not just received. A later remote step receives
 * what earlier steps of the same run stored, remote or inline.
 */
// The reaper's stalled-run sweep also advances runs; an hour apart it cannot be what moves a run here.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.workflow.reaper-interval-ms=3600000")
@AutoConfigureTestRestTemplate
@Import(StoringStepTestConfig.class)
class RemoteStepRoundTripIT extends AbstractWorkflowHttpIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private MediaJobs mediaJobs;

    @Autowired
    private TransactionTemplate transactionTemplate;

    record StartUploadRunView(UUID uploadId, String originalName) {
    }

    record StepView(UUID id, int stepOrder, String status) {
    }

    record StartedRunView(UUID id, String status, String input, List<StepView> steps) {
    }

    /** A probe run started over HTTP whose job this test has already taken off the jobs queue. */
    record DispatchedRun(UUID runId, UUID stepId, String objectKey, Message job, String token) {
    }

    @BeforeEach
    void emptyTheQueuesThisTestReads() {
        for (String queue : List.of("media.jobs", "media.results.dlq", "media.unrouted")) {
            amqpAdmin.purgeQueue(queue, false);
        }
    }

    private DispatchedRun startProbeRun(String prefix) {
        return startProbeRun(prefix, "clip.mp4");
    }

    private DispatchedRun startProbeRun(String prefix, String originalName) {
        StartedProbeRun started = startProbeRunWithoutReceivingTheJob(prefix, originalName);
        return new DispatchedRun(started.runId(), started.stepId(), started.objectKey(), receiveJob(started.stepId()),
                started.token());
    }

    record StartedProbeRun(UUID runId, UUID stepId, String objectKey, String input, String token) {
    }

    private StartedProbeRun startProbeRunWithoutReceivingTheJob(String prefix, String originalName) {
        UserView user = newUser(prefix);
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("Inspect the file", "probe", null));
        UUID uploadId = UUID.randomUUID();
        String objectKey = user.id() + "/" + uploadId;
        putClip(objectKey);
        String token = loginAs(user);

        ResponseEntity<StartedRunView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartUploadRunView(uploadId, originalName), StartedRunView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new StartedProbeRun(started.getBody().id(), started.getBody().steps().getFirst().id(), objectKey,
                started.getBody().input(), token);
    }

    @Test
    void theJobLeavesAsTheBareContractBodyAndTheWorkersResultCompletesTheStep() throws IOException {
        DispatchedRun run = startProbeRun("roundtrip");

        MessageProperties properties = run.job().getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(run.stepId().toString());
        assertThat(properties.getCorrelationId()).isEqualTo(run.runId().toString());
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        JsonNode body = JSON.readTree(run.job().getBody());
        assertThat(jobSchema().validate(body)).isEmpty();
        assertThat(body.get("inputs")).isEqualTo(JSON.readTree(
                "[{\"key\":\"media\",\"bucket\":\"" + UPLOAD_BUCKET + "\",\"objectKey\":\"" + run.objectKey() + "\"}]"));

        publish("progress", "{\"schemaVersion\":\"1\",\"jobId\":\"" + run.stepId() + "\",\"runId\":\"" + run.runId()
                + "\",\"attempt\":1,\"deliveryCount\":0,\"kind\":\"started\",\"at\":\"" + Instant.now() + "\"}", run);
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT lease_until - picked_up_at FROM run_step WHERE id = ?", String.class, run.stepId()))
                .isEqualTo("00:02:00"));

        publish("result", completedResult(run), run);
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM run_step WHERE id = ?", String.class, run.stepId())).isEqualTo("completed"));

        Map<String, Object> step = jdbc.queryForMap("SELECT output, outputs::text AS outputs FROM run_step WHERE id = ?",
                run.stepId());
        assertThat(JSON.readTree((String) step.get("output"))).isEqualTo(JSON.readTree("{\"durationMs\":1000}"));
        assertThat(JSON.readTree((String) step.get("outputs")).get(0).get("name").asString()).isEqualTo("video-details.json");
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM workflow_run WHERE id = ?", String.class, run.runId())).isEqualTo("completed"));

        ResponseEntity<String> read = get("/api/v1/runs/" + run.runId(), run.token(), String.class);
        assertThat(read.getBody()).contains("\"input\":\"clip.mp4\"").doesNotContain(UPLOAD_BUCKET, run.objectKey());
    }

    @Test
    void aLaterRemoteStepGetsTheUploadAnEarlierRemoteOutputAndAnInlineStepsObjectWhichIsNotDownloadable()
            throws IOException {
        UserView user = newUser("roundtrip-handoff");
        WorkflowView workflow = newWorkflow(user.id(), false,
                new NewWorkflowStep("Inspect the file", "probe", null),
                new NewWorkflowStep("Voice it", StoringStepTestConfig.TYPE, null),
                new NewWorkflowStep("Compose", "extract_audio", null, "{\"label\":\"x\",\"outputInputs\":["
                        + "{\"stepOrder\":1,\"outputName\":\"video-details.json\",\"inputKey\":\"details\"},"
                        + "{\"stepOrder\":2,\"outputName\":\"" + StoringStepTestConfig.OBJECT_NAME
                        + "\",\"inputKey\":\"voice\"},"
                        + "{\"stepOrder\":1,\"outputName\":\"accompaniment.wav\",\"inputKey\":\"music\","
                        + "\"optional\":true}]}"));
        UUID uploadId = UUID.randomUUID();
        String objectKey = user.id() + "/" + uploadId;
        putClip(objectKey);
        String token = loginAs(user);
        StartedRunView started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartUploadRunView(uploadId, "clip.mp4"), StartedRunView.class).getBody();
        DispatchedRun probe = new DispatchedRun(started.id(), started.steps().get(0).id(), objectKey,
                receiveJob(started.steps().get(0).id()), token);

        publish("result", completedResult(probe), probe);
        Message compose = receiveJob(started.steps().get(2).id(), Duration.ofSeconds(10));

        JsonNode body = JSON.readTree(compose.getBody());
        assertThat(jobSchema().validate(body)).isEmpty();
        String voiceKey = "runs/" + probe.runId() + "/steps/2/" + StoringStepTestConfig.OBJECT_NAME;
        assertThat(body.get("inputs")).isEqualTo(JSON.readTree("["
                + "{\"key\":\"media\",\"bucket\":\"" + UPLOAD_BUCKET + "\",\"objectKey\":\"" + objectKey + "\"},"
                + "{\"key\":\"details\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\"runs/" + probe.runId()
                + "/steps/1/attempts/1/video-details.json\"},"
                + "{\"key\":\"voice\",\"bucket\":\"" + UPLOAD_BUCKET + "\",\"objectKey\":\"" + voiceKey + "\"}]"));
        assertThat(body.get("params").get("label").asString()).isEqualTo("x");
        try (S3Client store = objectStore()) {
            assertThat(store.headObject(o -> o.bucket(UPLOAD_BUCKET).key(voiceKey)).contentLength()).isEqualTo(4L);
        }

        ResponseEntity<String> url = get("/api/v1/runs/" + probe.runId() + "/steps/2/outputs/"
                + StoringStepTestConfig.OBJECT_NAME + "/url", token, String.class);
        assertThat(url.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aMalformedResultLandsOnTheResultsDeadLetterQueueAndTheStepIsUnchanged() {
        DispatchedRun run = startProbeRun("roundtrip-malformed");
        String malformed = "{\"jobId\":\"" + run.stepId() + "\",\"status\":\"done\"}";

        publish("result", malformed, run);

        Message dead = rabbit.receive("media.results.dlq", WAIT.toMillis());
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(malformed);
        assertThat(jdbc.queryForObject("SELECT status FROM run_step WHERE id = ?", String.class, run.stepId()))
                .isEqualTo("running");
    }

    @Test
    void aResultWhoseOutputLiesInAnotherRunLandsOnTheResultsDeadLetterQueueAndTheStepIsUnchanged() {
        DispatchedRun run = startProbeRun("roundtrip-foreign-output");
        String foreign = completedResult(run)
                .replace("runs/" + run.runId() + "/", "runs/" + UUID.randomUUID() + "/");

        publish("result", foreign, run);

        Message dead = rabbit.receive("media.results.dlq", WAIT.toMillis());
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(foreign);
        assertThat(jdbc.queryForObject("SELECT status FROM run_step WHERE id = ?", String.class, run.stepId()))
                .isEqualTo("running");
    }

    @Test
    void anotherMembersUploadIsRefusedAndNoJobIsPublished() {
        UserView owner = newUser("roundtrip-owner");
        UUID ownersUpload = UUID.randomUUID();
        putClip(owner.id() + "/" + ownersUpload);
        UserView intruder = newUser("roundtrip-intruder");
        WorkflowView workflow = newWorkflow(intruder.id(), false, new NewWorkflowStep("Inspect", "probe", null));

        ResponseEntity<String> response = post("/api/v1/workflows/" + workflow.id() + "/runs", loginAs(intruder),
                new StartUploadRunView(ownersUpload, "clip.mp4"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"uploadId\":\"Upload the file again\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                workflow.id())).isZero();
        verify(AopTestUtils.<MediaJobs>getUltimateTargetObject(mediaJobs), never()).enqueue(any());
    }

    @Test
    void aClaimTransactionThatRollsBackPublishesNothingAndRecordsNoPublication() {
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        MediaJob job = new MediaJob("1", jobId, runId, runId, 1, MediaCapability.PROBE,
                List.of(new MediaJobInput("media", UPLOAD_BUCKET, UUID.randomUUID() + "/" + UUID.randomUUID())),
                Map.of(), "runs/" + runId + "/steps/1/", 1, 300, 524_288_000L, Instant.now());

        transactionTemplate.executeWithoutResult(status -> {
            mediaJobs.enqueue(job);
            status.setRollbackOnly();
        });

        assertNoJobFor(jobId, Duration.ofSeconds(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                Long.class, "%" + jobId + "%")).isZero();
    }

    @Test
    void aClaimThatRollsBackAfterTheJobWasRecordedLeavesTheStepPendingAndPublishesNothing() {
        // The field holds the transactional proxy; the spy is the object behind it.
        MediaJobs spy = AopTestUtils.getUltimateTargetObject(mediaJobs);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("the claim fails after the job was recorded");
        }).when(spy).enqueue(any());

        StartedProbeRun run = startProbeRunWithoutReceivingTheJob("roundtrip-claim-rollback", "clip.mp4");

        verify(spy, timeout(WAIT.toMillis())).enqueue(any());
        assertNoJobFor(run.stepId(), Duration.ofSeconds(2));
        assertThat(jdbc.queryForObject("SELECT status FROM run_step WHERE id = ?", String.class, run.stepId()))
                .isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE event_type LIKE ? "
                + "AND serialized_event LIKE ?", Long.class, "%MediaJobRequested%", "%" + run.stepId() + "%")).isZero();
    }

    @Test
    void aLongFileNameIsKeptAsItsFirst255Characters() {
        StartedProbeRun run = startProbeRunWithoutReceivingTheJob("roundtrip-long-name", "x".repeat(296) + ".mp4");

        assertThat(run.input()).isEqualTo("x".repeat(255));
        assertThat(jdbc.queryForObject("SELECT input FROM workflow_run WHERE id = ?", String.class, run.runId()))
                .hasSize(255);
        receiveJob(run.stepId());
    }

    @Test
    void aMessageWithAnUnboundKeyIsKeptOnTheUnroutedQueue() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        String body = "{\"lost\":\"" + UUID.randomUUID() + "\"}";

        rabbit.send("media", "no-such-key", new Message(body.getBytes(StandardCharsets.UTF_8), properties));

        Message kept = rabbit.receive("media.unrouted", WAIT.toMillis());
        assertThat(kept).isNotNull();
        assertThat(new String(kept.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
    }

    private Message receiveJob(UUID stepId) {
        return receiveJob(stepId, WAIT);
    }

    private Message receiveJob(UUID stepId, Duration wait) {
        Instant deadline = Instant.now().plus(wait);
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive("media.jobs", 1_000);
            if (message != null && stepId.toString().equals(message.getMessageProperties().getMessageId())) {
                return message;
            }
        }
        throw new AssertionError("No job for step " + stepId + " reached media.jobs within " + wait);
    }

    /** Other tests' jobs may still arrive here; only one for {@code stepId} counts. */
    private void assertNoJobFor(UUID stepId, Duration window) {
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive("media.jobs", 200);
            assertThat(message == null ? null : message.getMessageProperties().getMessageId())
                    .isNotEqualTo(stepId.toString());
        }
    }

    private void publish(String routingKey, String body, DispatchedRun run) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setCorrelationId(run.runId().toString());
        rabbit.send("media", routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private static String completedResult(DispatchedRun run) {
        return "{\"schemaVersion\":\"1\",\"jobId\":\"" + run.stepId() + "\",\"runId\":\"" + run.runId() + "\","
                + "\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\",\"outputs\":[{\"name\":"
                + "\"video-details.json\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\"runs/" + run.runId()
                + "/steps/1/attempts/1/video-details.json\",\"mime\":\"application/json\",\"bytes\":42}],"
                + "\"data\":{\"durationMs\":1000},\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"test-worker\"}";
    }

    private static void putClip(String objectKey) {
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(objectKey).contentType("video/mp4"),
                    RequestBody.fromBytes("a one-second clip".getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static Schema jobSchema() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see boot/app/build.gradle.kts)");
        try (InputStream in = Files.newInputStream(Path.of(dir, "media-job.schema.json"))) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(JSON.readTree(in));
        }
    }
}
