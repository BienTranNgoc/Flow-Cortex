package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.user.api.UserView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The script revision slice end to end against a real Postgres, broker and object store: this test
 * plays the provider worker so a Product Video run reaches its checkpoint, the script is edited into
 * immutable revisions, and approval names the revision it saw. The three gate conditions live here —
 * a stale revision cannot be approved, a concurrent second approval loses, and no speech job is
 * dispatched before an approval lands.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.product-video.enabled=true")
@AutoConfigureTestRestTemplate
class ScriptRevisionApprovalIT extends AbstractWorkflowHttpIT {

    private static final Logger LOG = LoggerFactory.getLogger(ScriptRevisionApprovalIT.class);
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String SCRIPT = "Mua ngay mau sua chinh hang";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @MockitoSpyBean
    private MediaJobs mediaJobs;

    @BeforeEach
    void enableMediaEngineAndDrainProviderQueues() {
        org.mockito.Mockito.when(mediaJobs.isEnabled()).thenReturn(true);
        amqpAdmin.purgeQueue("provider.jobs", false);
    }

    @Test
    void aCheckpointArrivesWithTheFirstScriptRevisionAndNoApprovedRevision() {
        CheckpointRun run = startUntilCheckpoint();

        ReviewTaskResponseView task = singlePendingTask(run);
        assertThat(task.currentRevisionId()).isNotNull();
        assertThat(task.approvedRevisionId()).isNull();
        assertThat(task.originalPayload()).isEqualTo(SCRIPT);
        assertThat(revisions(run, task.id())).extracting(ScriptRevisionResponseView::content)
                .containsExactly(SCRIPT);
        assertThat(revisions(run, task.id()).getFirst().revisionNumber()).isEqualTo(1);
    }

    @Test
    void editingTheScriptStoresANewRevisionAndMovesTheCurrentPointer() {
        CheckpointRun run = startUntilCheckpoint();
        ReviewTaskResponseView task = singlePendingTask(run);
        long version = task.version();

        ScriptRevisionResponseView saved = saveRevision(run, task.id(), "Sửa kịch bản: giảm giá 20%", version);

        assertThat(saved.revisionNumber()).isEqualTo(2);
        assertThat(saved.content()).isEqualTo("Sửa kịch bản: giảm giá 20%");
        assertThat(saved.reviewTaskVersion()).isGreaterThan(version);
        assertThat(revisions(run, task.id())).extracting(ScriptRevisionResponseView::content)
                .containsExactly(SCRIPT, "Sửa kịch bản: giảm giá 20%");
    }

    @Test
    void aStaleExpectedVersionIsRejectedWithConflict() {
        CheckpointRun run = startUntilCheckpoint();
        ReviewTaskResponseView task = singlePendingTask(run);
        saveRevision(run, task.id(), "Sửa lần một", task.version());

        ResponseEntity<String> stale = post("/api/v1/review-tasks/" + task.id() + "/revisions", run.token(),
                new SaveRevisionView("Sửa lần hai trên tab cũ", task.version()), String.class);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(revisions(run, task.id())).hasSize(2);
    }

    @Test
    void approvingAnEarlierRevisionOnceANewerOneExistsIsAConflict() {
        CheckpointRun run = startUntilCheckpoint();
        ReviewTaskResponseView task = singlePendingTask(run);
        UUID stale = task.currentRevisionId();
        ScriptRevisionResponseView saved = saveRevision(run, task.id(), "Kịch bản mới nhất", task.version());

        ResponseEntity<String> approved = post("/api/v1/review-tasks/" + task.id() + "/approve", run.token(),
                new ApproveScriptView(stale, saved.reviewTaskVersion()), String.class);

        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(workflowRunService.getRun(run.ownerId(), run.runId()).status()).isEqualTo("waiting_approval");
    }

    @Test
    void approvingWithoutARevisionOnAScriptTaskIsAConflict() {
        CheckpointRun run = startUntilCheckpoint();
        ReviewTaskResponseView task = singlePendingTask(run);

        ResponseEntity<String> approved = post("/api/v1/review-tasks/" + task.id() + "/approve", run.token(),
                Map.of("reviewedPayload", "né chỉnh sửa trực tiếp"), String.class);

        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aSecondApprovalOfTheSameRevisionIsAConflictSoOnlyOneTabWins() {
        CheckpointRun run = startUntilCheckpoint();
        ReviewTaskResponseView task = singlePendingTask(run);
        long version = task.version();

        ResponseEntity<String> first = post("/api/v1/review-tasks/" + task.id() + "/approve", run.token(),
                new ApproveScriptView(task.currentRevisionId(), version), String.class);
        ResponseEntity<String> second = post("/api/v1/review-tasks/" + task.id() + "/approve", run.token(),
                new ApproveScriptView(task.currentRevisionId(), version), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void noSpeechJobIsDispatchedUntilARevisionIsApproved() {
        CheckpointRun run = startUntilCheckpoint();
        ReviewTaskResponseView task = singlePendingTask(run);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE event_type LIKE '%MediaJobRequested%' "
                        + "AND serialized_event LIKE ?", Long.class, "%" + run.runId() + "%")).isZero();
        assertThat(receiveProviderJob(run.runId(), null, Duration.ofSeconds(2))).isNull();
    }

    @Test
    void theApprovedRevisionIsTheScriptTheSpeechJobCarries() {
        CheckpointRun run = startUntilCheckpoint();
        ReviewTaskResponseView task = singlePendingTask(run);
        ScriptRevisionResponseView saved = saveRevision(run, task.id(), "Kịch bản được duyệt", task.version());

        ResponseEntity<ReviewTaskResponseView> approved = post("/api/v1/review-tasks/" + task.id() + "/approve",
                run.token(), new ApproveScriptView(saved.id(), saved.reviewTaskVersion()),
                ReviewTaskResponseView.class);

        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().approvedRevisionId()).isEqualTo(saved.id());
        LOG.info("DIAG step1 status after approval = " + jdbc.queryForObject(
                "SELECT status FROM run_step WHERE run_id = ? AND step_order = 1", String.class, run.runId()));
        LOG.info("DIAG step2 status after approval = " + jdbc.queryForObject(
                "SELECT status FROM run_step WHERE run_id = ? AND step_order = 2", String.class, run.runId()));
        LOG.info("DIAG review_task rows = " + jdbc.queryForObject("SELECT count(*) FROM review_task WHERE run_id = ?",
                Long.class, run.runId()));

        // The visual storyboard step now sits between approval and speech (see
        // V2026.10.17.002). Speech is not enqueued until the storyboard provider completes, so
        // play it before the speech assertion — this test is about the speech payload, not the
        // storyboard's content.
        Message storyboard = receiveProviderJob(run.runId(), "generate_storyboard", WAIT);
        assertThat(storyboard).as("the storyboard job is dispatched after approval").isNotNull();
        publishStoryboardResult(run.runId(), messageId(storyboard), saved.id());

        Message speech = receiveProviderJob(run.runId(), "generate_speech", WAIT);
        assertThat(speech).as("the speech job carries the approved revision").isNotNull();
        String body = new String(speech.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("generate_speech")
                .contains("Kịch bản được duyệt")
                .doesNotContain(SCRIPT);
    }

    @Test
    void anotherUserCannotReadEditOrApproveARevision() {
        CheckpointRun run = startUntilCheckpoint();
        ReviewTaskResponseView task = singlePendingTask(run);
        String strangerToken = loginAs(newUser("script-stranger"));

        assertThat(get("/api/v1/review-tasks/" + task.id() + "/revisions", strangerToken, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/review-tasks/" + task.id() + "/revisions", strangerToken,
                new SaveRevisionView("x", task.version()), String.class).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/review-tasks/" + task.id() + "/approve", strangerToken,
                new ApproveScriptView(task.currentRevisionId(), task.version()), String.class).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(singlePendingTask(run).approvedRevisionId()).isNull();
    }

    /** Starts a Product Video run and plays the script provider until the run pauses. */
    private CheckpointRun startUntilCheckpoint() {
        UserView owner = newUser("script-revision");
        String token = loginAs(owner);
        UUID uploadId = uploadProductImage(owner);

        ResponseEntity<RunResponseView> started = post("/api/v1/product-videos/runs", token,
                new ProductVideoView("Mực Nét", "Nét chữ đậm, không lem", "Mua ngay hôm nay", List.of(uploadId),
                        new ProductVideoView.VideoConfigView("9:16")),
                RunResponseView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = started.getBody().id();
        // The job leaves through the outbox on its own transaction; wait for it to reach the queue
        // before answering it, otherwise the result races ahead of a job that does not exist yet.
        Message scriptJob = receiveJobFor(runId, "generate_script", WAIT);
        publishScriptResult(runId, SCRIPT, messageId(scriptJob));
        try {
            pollUntil(owner.id(), runId, run -> "waiting_approval".equals(run.status()), WAIT);
        } catch (AssertionError e) {
            LOG.info("DIAG run=" + runId + " step1=" + stepStatus(runId, 1) + " step2=" + stepStatus(runId, 2)
                    + " runStatus=" + jdbc.queryForObject("SELECT status FROM workflow_run WHERE id = ?", String.class,
                            runId)
                    + " tasks=" + jdbc.queryForObject("SELECT count(*) FROM review_task WHERE run_id = ?", Long.class,
                            runId));
            throw e;
        }
        return new CheckpointRun(runId, owner.id(), token);
    }

    private String stepStatus(UUID runId, int order) {
        return jdbc.queryForObject("SELECT status FROM run_step WHERE run_id = ? AND step_order = ?", String.class,
                runId, order);
    }

    /** The first job of this run and capability to reach {@code provider.jobs}, or fail. */
    private Message receiveJobFor(UUID runId, String capability, Duration window) {
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive("provider.jobs", 200);
            if (message != null && new String(message.getBody(), StandardCharsets.UTF_8).contains(runId.toString())
                    && new String(message.getBody(), StandardCharsets.UTF_8).contains(capability)) {
                return message;
            }
        }
        throw new AssertionError("No " + capability + " job for run " + runId + " reached provider.jobs within "
                + window);
    }

    /** The provider's answer for step 1: the shape the checkpoint unwraps. */
    private void publishScriptResult(UUID runId, String script, String messageId) {
        UUID stepId = UUID.fromString(messageId);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setCorrelationId(runId.toString());
        String body = "{\"schemaVersion\":\"1\",\"jobId\":\"" + stepId + "\",\"runId\":\"" + runId + "\","
                + "\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\",\"data\":{\"profile\":\"product_ad_v1\","
                + "\"language\":\"vi\",\"script\":\"" + script + "\"},\"finishedAt\":\"" + Instant.now() + "\","
                + "\"workerId\":\"test-provider\"}";
        rabbit.send("provider", "result", new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    /** The provider's answer for the storyboard step so the orchestrator advances to speech. */
    private void publishStoryboardResult(UUID runId, String storyboardMessageId, UUID sourceRevisionId) {
        UUID stepId = UUID.fromString(storyboardMessageId);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setCorrelationId(runId.toString());
        String body = "{\"schemaVersion\":\"1\",\"jobId\":\"" + stepId + "\",\"runId\":\"" + runId + "\","
                + "\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\","
                + "\"data\":{\"profile\":\"product_ad_storyboard_v1\","
                + "\"sourceRevisionId\":\"" + sourceRevisionId + "\","
                + "\"scene1Prompt\":\"Mở cảnh sản phẩm\","
                + "\"scene2Prompt\":\"Cảnh lợi ích và CTA\"},"
                + "\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"test-provider\"}";
        rabbit.send("provider", "result", new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    /** The job's {@code jobId} is its message id, which the producer sets to the step id. */
    private String messageId(Message job) {
        assertThat(job).as("the dispatched job carries its step as message id").isNotNull();
        return job.getMessageProperties().getMessageId();
    }

    private UUID uploadProductImage(UserView owner) {
        ResponseEntity<UploadResponseView> grant = post("/api/v1/uploads", loginAs(owner),
                new UploadRequestView("product.png", 16L), UploadResponseView.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UploadResponseView body = grant.getBody();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(owner.id() + "/" + body.uploadId())
                    .contentType("image/png"), RequestBody.fromBytes("sixteen bytes!!!".getBytes()));
        } catch (RuntimeException e) {
            throw new IllegalStateException("could not stage the product image", e);
        }
        return body.uploadId();
    }

    /** The first job for this run and capability to reach {@code provider.jobs}, or {@code null} when
     * none arrives inside the window. A {@code null} or blank capability matches any job, which the
     * "no job is dispatched yet" test uses. After the visual-storyboard step was inserted at
     * {@code step_order = 3}, a call without a capability filter would pick up that storyboard job
     * before the speech job at {@code step_order = 4} and the speech assertion would race it. */
    private Message receiveProviderJob(UUID runId, String capability, Duration window) {
        Instant deadline = Instant.now().plus(window);
        String token = "\"" + capability + "\"";
        boolean filterCapability = capability != null && !capability.isBlank();
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive("provider.jobs", 200);
            if (message == null) {
                continue;
            }
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (!body.contains(runId.toString())) {
                continue;
            }
            if (filterCapability && !body.contains(token)) {
                continue;
            }
            return message;
        }
        return null;
    }

    private ReviewTaskResponseView singlePendingTask(CheckpointRun run) {
        ResponseEntity<ReviewTaskResponseView[]> tasks =
                get("/api/v1/runs/" + run.runId() + "/review-tasks", run.token(), ReviewTaskResponseView[].class);
        assertThat(tasks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tasks.getBody()).hasSize(1);
        assertThat(tasks.getBody()[0].status()).isEqualTo("pending");
        return tasks.getBody()[0];
    }

    private ScriptRevisionResponseView saveRevision(CheckpointRun run, UUID taskId, String content, long version) {
        ResponseEntity<ScriptRevisionResponseView> saved = post("/api/v1/review-tasks/" + taskId + "/revisions",
                run.token(), new SaveRevisionView(content, version), ScriptRevisionResponseView.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        return saved.getBody();
    }

    private List<ScriptRevisionResponseView> revisions(CheckpointRun run, UUID taskId) {
        ResponseEntity<ScriptRevisionResponseView[]> response =
                get("/api/v1/review-tasks/" + taskId + "/revisions", run.token(), ScriptRevisionResponseView[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    record CheckpointRun(UUID runId, UUID ownerId, String token) {
    }

    record SaveRevisionView(String content, Long expectedVersion) {
    }

    record ApproveScriptView(UUID revisionId, Long expectedVersion) {
    }

    record ProductVideoView(String productName, String description, String cta, List<UUID> uploadIds,
            VideoConfigView video) {

        record VideoConfigView(String aspectRatio) {
        }
    }

    record UploadRequestView(String fileName, Long size) {
    }

    record UploadResponseView(UUID uploadId, String url, String method, Map<String, String> headers,
            Instant expiresAt, long maxBytes) {
    }

    record ScriptRevisionResponseView(UUID id, UUID runId, UUID reviewTaskId, int revisionNumber, String content,
            UUID createdBy, Instant createdAt, Long reviewTaskVersion) {
    }

    record ReviewTaskResponseView(UUID id, UUID runId, UUID runStepId, String reviewType, String status,
            String originalPayload, String reviewedPayload, UUID reviewedBy, Instant reviewedAt, Instant createdAt,
            Long version, UUID currentRevisionId, UUID approvedRevisionId) {
    }
}
