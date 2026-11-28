package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.flowcortex.app.media.ImageFixtures;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaControlResponse;
import com.flowcortex.user.api.UserView;
import com.rabbitmq.client.AMQP;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "flowcortex.product-video.enabled=true",
                "flowcortex.product-video.mc-enabled=true",
                "flowcortex.product-video.parallel-clips-enabled=true",
                "flowcortex.workflow.cancel.enabled=true",
                "flowcortex.workflow.reaper-interval-ms=3600000"
        })
@AutoConfigureTestRestTemplate
class ProductVideoParallelRoundTripIT extends AbstractWorkflowHttpIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String SCRIPT = "San pham chinh hang chat luong cao";

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @MockitoSpyBean
    private MediaJobs mediaJobs;

    @BeforeEach
    void enableMediaAndDrainQueues() {
        doReturn(true).when(AopTestUtils.<MediaJobs>getUltimateTargetObject(mediaJobs)).isEnabled();
        amqpAdmin.purgeQueue("provider.jobs", false);
        amqpAdmin.purgeQueue("provider.video.jobs", false);
        amqpAdmin.purgeQueue("media.render.jobs", false);
        amqpAdmin.purgeQueue("provider.control", false);
    }

    @Test
    void dispatchesBothClipsBeforeEitherCompletesAndComposesInOrderWhenEightFinishesFirst() throws Exception {
        ParallelRun run = startAndAdvanceToParallelClips("pv-parallel-ok");

        // Both clips must receive CONTINUE from backend over the dedicated control RPC.
        assertThat(requestControl(run.runId(), run.clip7().jobId(), 1).decision())
                .isEqualTo(MediaControlResponse.Decision.CONTINUE);
        assertThat(requestControl(run.runId(), run.clip8().jobId(), 1).decision())
                .isEqualTo(MediaControlResponse.Decision.CONTINUE);

        // Clip 8 completes first: lipsync_video must wait until clip 7 finishes too.
        publishVideoClipSuccess(run.runId(), run.clip8(), run.revisionId(), "pred-clip-8");
        assertThat(receiveQueueJobFor(run.runId(), "provider.jobs", "lipsync_video", Duration.ofMillis(700)))
                .isNull();

        // Clip 7 completes second: lipsync_video dispatches for step 9, then render_video composes in order.
        publishVideoClipSuccess(run.runId(), run.clip7(), run.revisionId(), "pred-clip-7");
        Message lipsyncMessage = receiveQueueJobFor(run.runId(), "provider.jobs", "lipsync_video", WAIT);
        assertThat(lipsyncMessage).isNotNull();
        ClipJob lipsyncJob = toClipJob(lipsyncMessage);
        assertThat(lipsyncJob.stepOrder()).isEqualTo(9);
        publishLipsyncSuccess(run.runId(), lipsyncJob, run.revisionId(), "pred-lipsync-9");

        Message renderMessage = receiveQueueJobFor(run.runId(), "media.render.jobs", "render_video", WAIT);
        assertThat(renderMessage).isNotNull();
        JsonNode renderJson = JSON.readTree(renderMessage.getBody());
        List<String> inputKeys = renderJson.get("inputs").valueStream()
                .map(node -> node.get("key").asString())
                .toList();
        List<String> objectKeys = renderJson.get("inputs").valueStream()
                .map(node -> node.get("objectKey").asString())
                .toList();
        int video1Idx = inputKeys.indexOf("generated_video_1");
        int video2Idx = inputKeys.indexOf("generated_video_2");
        assertThat(inputKeys).contains("speech", "generated_video_1", "generated_video_2");
        assertThat(video1Idx).isLessThan(video2Idx);
        assertThat(objectKeys.get(video1Idx)).contains("/steps/9/");
        assertThat(objectKeys.get(video2Idx)).contains("/steps/8/");

        // Complete render_video and confirm the run reaches completed without a second render dispatch.
        UUID renderJobId = UUID.fromString(renderJson.get("jobId").asString());
        String renderObjectKey = "runs/" + run.runId() + "/steps/10/attempts/1/video.mp4";
        putMediaObject(renderObjectKey, "video/mp4", new byte[] {0, 0, 0, 1});
        publishResult("media", "{\"schemaVersion\":\"1\",\"jobId\":\"" + renderJobId + "\",\"runId\":\""
                + run.runId() + "\",\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\","
                + "\"outputs\":[{\"name\":\"video.mp4\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\""
                + renderObjectKey + "\",\"mime\":\"video/mp4\",\"bytes\":4}],"
                + "\"data\":{\"durationMs\":10000,\"width\":480,\"height\":854},\"finishedAt\":\""
                + Instant.now() + "\",\"workerId\":\"media-it\"}", run.runId());

        var completed = pollUntil(run.ownerId(), run.runId(), r -> "completed".equals(r.status()), WAIT);
        assertThat(completed.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("completed"));
        assertThat(receiveQueueJobFor(run.runId(), "media.render.jobs", "render_video", Duration.ofMillis(400)))
                .isNull();
    }

    @Test
    void controlProtocolReturnsCancelSiblingFailedAndDiscardStaleOverRabbitMq() throws Exception {
        ParallelRun run = startAndAdvanceToParallelClips("pv-parallel-cancel");

        // Clip 8 fails terminally; run fails with clip 8's message and sibling receives cancel decision.
        publishResult("provider", "{\"schemaVersion\":\"1\",\"jobId\":\"" + run.clip8().jobId()
                + "\",\"runId\":\"" + run.runId() + "\",\"attempt\":1,\"deliveryCount\":0,\"status\":\"error\","
                + "\"error\":{\"code\":\"provider_request_failed\",\"message\":\"wan clip 2 error\","
                + "\"retryable\":false},\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"video-it\"}",
                run.runId());

        var failed = pollUntil(run.ownerId(), run.runId(), r -> "error".equals(r.status()), WAIT);
        assertThat(failed.errorMessage()).isEqualTo("wan clip 2 error");

        assertThat(requestControl(run.runId(), run.clip7().jobId(), 1).decision())
                .isEqualTo(MediaControlResponse.Decision.CANCEL_SIBLING_FAILED);
        assertThat(requestControl(run.runId(), run.clip8().jobId(), 1).decision())
                .isEqualTo(MediaControlResponse.Decision.DISCARD_STALE);

        // Clip 7 settles with sibling_failed: preserves first error message and never dispatches downstream jobs.
        publishResult("provider", "{\"schemaVersion\":\"1\",\"jobId\":\"" + run.clip7().jobId()
                + "\",\"runId\":\"" + run.runId() + "\",\"attempt\":1,\"deliveryCount\":0,\"status\":\"error\","
                + "\"error\":{\"code\":\"sibling_failed\",\"message\":\"sibling clip failed\","
                + "\"retryable\":false},\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"video-it\"}",
                run.runId());

        var settledRun = pollUntil(run.ownerId(), run.runId(),
                r -> r.steps().stream().noneMatch(step -> "running".equals(step.status())), WAIT);
        assertThat(settledRun.errorMessage()).isEqualTo("wan clip 2 error");
        assertThat(settledRun.steps().get(6).errorCode()).isEqualTo("sibling_failed");
        assertThat(requestControl(run.runId(), run.clip7().jobId(), 1).decision())
                .isEqualTo(MediaControlResponse.Decision.DISCARD_STALE);
        assertThat(receiveQueueJobFor(run.runId(), "provider.jobs", "lipsync_video", Duration.ofMillis(400)))
                .isNull();
        assertThat(receiveQueueJobFor(run.runId(), "media.render.jobs", "render_video", Duration.ofMillis(400)))
                .isNull();
    }

    @Test
    void stoppingRunKeepsBothRunningClipsGoingUntilBothFinish() throws Exception {
        ParallelRun run = startAndAdvanceToParallelClips("pv-parallel-stop");

        ResponseEntity<RunResponseView> cancelled = post("/api/v1/runs/" + run.runId() + "/cancel",
                run.token(), null, RunResponseView.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().status()).isEqualTo("cancelling");
        assertThat(cancelled.getBody().steps().get(8).status()).isEqualTo("skipped");
        assertThat(cancelled.getBody().steps().get(9).status()).isEqualTo("skipped");

        assertThat(requestControl(run.runId(), run.clip7().jobId(), 1).decision())
                .isEqualTo(MediaControlResponse.Decision.CONTINUE);
        assertThat(requestControl(run.runId(), run.clip8().jobId(), 1).decision())
                .isEqualTo(MediaControlResponse.Decision.CONTINUE);

        publishVideoClipSuccess(run.runId(), run.clip7(), run.revisionId(), "pred-stop-7");
        var mid = workflowRunService.getRun(run.ownerId(), run.runId());
        assertThat(mid.status()).isEqualTo("cancelling");

        publishVideoClipSuccess(run.runId(), run.clip8(), run.revisionId(), "pred-stop-8");
        var done = pollUntil(run.ownerId(), run.runId(), r -> "cancelled".equals(r.status()), WAIT);
        assertThat(done.steps().get(6).status()).isEqualTo("completed");
        assertThat(done.steps().get(7).status()).isEqualTo("completed");
        assertThat(done.steps().get(8).status()).isEqualTo("skipped");
        assertThat(done.steps().get(9).status()).isEqualTo("skipped");
    }

    private ParallelRun startAndAdvanceToParallelClips(String userPrefix) throws Exception {
        UserView owner = newUser(userPrefix);
        String token = loginAs(owner);
        UUID uploadId = uploadProductImage(owner, token);

        ResponseEntity<RunResponseView> started = post("/api/v1/product-videos/runs", token,
                new ProductVideoView("Binh nuoc", "Giu nhiet tot", "Mua ngay", List.of(uploadId),
                        new ProductVideoView.VideoConfigView("9:16"), "mc-v1", "mc-nu-tre"),
                RunResponseView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = started.getBody().id();

        Message scriptJob = receiveQueueJobFor(runId, "provider.jobs", "generate_script", WAIT);
        assertThat(scriptJob).isNotNull();
        publishResult("provider", "{\"schemaVersion\":\"1\",\"jobId\":\""
                + scriptJob.getMessageProperties().getMessageId() + "\",\"runId\":\"" + runId
                + "\",\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\","
                + "\"data\":{\"profile\":\"product_ad_v1\",\"language\":\"vi\",\"script\":\"" + SCRIPT + "\"},"
                + "\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"it-provider\"}", runId);

        pollUntil(owner.id(), runId, r -> "waiting_approval".equals(r.status()), WAIT);
        ReviewTaskResponseView scriptTask = findPendingReviewTask(runId, token);
        UUID revisionId = scriptTask.currentRevisionId();
        ResponseEntity<String> approved = post("/api/v1/review-tasks/" + scriptTask.id() + "/approve", token,
                new ApproveScriptView(revisionId, scriptTask.version()), String.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);

        Message speechJob = receiveQueueJobFor(runId, "provider.jobs", "generate_speech", WAIT);
        assertThat(speechJob).isNotNull();
        String speechKey = "runs/" + runId + "/steps/3/attempts/1/speech.wav";
        putMediaObject(speechKey, "audio/wav", new byte[] {'R', 'I', 'F', 'F'});
        publishResult("provider", "{\"schemaVersion\":\"1\",\"jobId\":\""
                + speechJob.getMessageProperties().getMessageId() + "\",\"runId\":\"" + runId
                + "\",\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\","
                + "\"outputs\":[{\"name\":\"speech.wav\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\""
                + speechKey + "\",\"mime\":\"audio/wav\",\"bytes\":4}],"
                + "\"data\":{\"profile\":\"vi_voice_v1\",\"durationMs\":4000,\"provider\":\"fixture\","
                + "\"voice\":\"silence\",\"speed\":\"+0%\",\"sourceRevisionId\":\"" + revisionId
                + "\",\"idempotencyKey\":\"" + "a".repeat(64) + "\",\"format\":\"wav\"},"
                + "\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"it-provider\"}", runId);

        Message storyboardJob = receiveQueueJobFor(runId, "provider.jobs", "generate_storyboard", WAIT);
        assertThat(storyboardJob).isNotNull();
        publishResult("provider", "{\"schemaVersion\":\"1\",\"jobId\":\""
                + storyboardJob.getMessageProperties().getMessageId() + "\",\"runId\":\"" + runId
                + "\",\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\","
                + "\"data\":{\"profile\":\"product_ad_storyboard_v1\",\"sourceRevisionId\":\"" + revisionId
                + "\",\"scene1Prompt\":\"Scene 1\",\"scene2Prompt\":\"Scene 2\"},"
                + "\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"it-provider\"}", runId);

        Message keyframeJob = receiveQueueJobFor(runId, "provider.jobs", "generate_keyframe", WAIT);
        assertThat(keyframeJob).isNotNull();
        String keyframeKey = "runs/" + runId + "/steps/5/attempts/1/keyframe.jpg";
        putMediaObject(keyframeKey, "image/jpeg", ImageFixtures.png());
        publishResult("provider", "{\"schemaVersion\":\"1\",\"jobId\":\""
                + keyframeJob.getMessageProperties().getMessageId() + "\",\"runId\":\"" + runId
                + "\",\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\","
                + "\"outputs\":[{\"name\":\"keyframe.jpg\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\""
                + keyframeKey + "\",\"mime\":\"image/jpeg\",\"bytes\":" + ImageFixtures.png().length + "}],"
                + "\"data\":{\"profile\":\"vertical_ad_keyframe_v1\",\"provider\":\"fixture\","
                + "\"model\":\"fixture-keyframe\",\"aspectRatio\":\"9:16\",\"width\":720,\"height\":1280,"
                + "\"sourceRevisionId\":\"" + revisionId + "\",\"idempotencyKey\":\"" + "d".repeat(64)
                + "\",\"providerTaskId\":\"pred-kf-5\",\"format\":\"jpg\"},"
                + "\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"it-provider\"}", runId);

        pollUntil(owner.id(), runId, r -> "waiting_approval".equals(r.status()), WAIT);
        ReviewTaskResponseView keyframeTask = findPendingReviewTask(runId, token);
        ResponseEntity<String> keyframeApproved = post("/api/v1/review-tasks/" + keyframeTask.id() + "/approve", token,
                null, String.class);
        assertThat(keyframeApproved.getStatusCode()).isEqualTo(HttpStatus.OK);

        Message firstVideo = receiveQueueJobFor(runId, "provider.video.jobs", "generate_video", WAIT);
        Message secondVideo = receiveQueueJobFor(runId, "provider.video.jobs", "generate_video", WAIT);
        assertThat(firstVideo).isNotNull();
        assertThat(secondVideo).isNotNull();
        ClipJob clipA = toClipJob(firstVideo);
        ClipJob clipB = toClipJob(secondVideo);
        ClipJob clip7 = clipA.stepOrder() == 7 ? clipA : clipB;
        ClipJob clip8 = clipA.stepOrder() == 8 ? clipA : clipB;
        assertThat(clip7.stepOrder()).isEqualTo(7);
        assertThat(clip8.stepOrder()).isEqualTo(8);
        return new ParallelRun(runId, owner.id(), token, revisionId, clip7, clip8);
    }

    private ReviewTaskResponseView findPendingReviewTask(UUID runId, String token) {
        ResponseEntity<ReviewTaskResponseView[]> tasks = get("/api/v1/runs/" + runId + "/review-tasks", token,
                ReviewTaskResponseView[].class);
        assertThat(tasks.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (ReviewTaskResponseView task : tasks.getBody()) {
            if ("pending".equals(task.status())) {
                return task;
            }
        }
        throw new AssertionError("No pending review task found for run " + runId);
    }

    private ClipJob toClipJob(Message msg) {
        JsonNode json = JSON.readTree(msg.getBody());
        return new ClipJob(UUID.fromString(json.get("jobId").asString()), json.get("stepOrder").asInt());
    }

    private void publishVideoClipSuccess(UUID runId, ClipJob clip, UUID revisionId, String providerTaskId) {
        String key = "runs/" + runId + "/steps/" + clip.stepOrder() + "/attempts/1/clip.mp4";
        putMediaObject(key, "video/mp4", new byte[] {0, 0, 0, 1});
        publishResult("provider", "{\"schemaVersion\":\"1\",\"jobId\":\"" + clip.jobId() + "\",\"runId\":\""
                + runId + "\",\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\","
                + "\"outputs\":[{\"name\":\"clip.mp4\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\""
                + key + "\",\"mime\":\"video/mp4\",\"bytes\":4}],"
                + "\"data\":{\"profile\":\"vertical_ad_clip_v2\",\"durationSeconds\":5,\"provider\":\"wan\","
                + "\"model\":\"wan-video/wan-2.2-5b-fast\",\"aspectRatio\":\"9:16\",\"resolution\":\"480p\","
                + "\"sourceRevisionId\":\"" + revisionId + "\",\"idempotencyKey\":\"" + "b".repeat(64)
                + "\",\"providerTaskId\":\"" + providerTaskId + "\",\"format\":\"mp4\"},"
                + "\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"video-it\"}", runId);
    }

    private void publishLipsyncSuccess(UUID runId, ClipJob clip, UUID revisionId, String providerTaskId) {
        String key = "runs/" + runId + "/steps/" + clip.stepOrder() + "/attempts/1/clip.mp4";
        putMediaObject(key, "video/mp4", new byte[] {0, 0, 0, 1});
        publishResult("provider", "{\"schemaVersion\":\"1\",\"jobId\":\"" + clip.jobId() + "\",\"runId\":\""
                + runId + "\",\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\","
                + "\"outputs\":[{\"name\":\"clip.mp4\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\""
                + key + "\",\"mime\":\"video/mp4\",\"bytes\":4}],"
                + "\"data\":{\"profile\":\"vertical_ad_lipsync_v1\",\"durationSeconds\":5,\"provider\":\"kling_lipsync\","
                + "\"model\":\"kling-lipsync\",\"aspectRatio\":\"9:16\",\"resolution\":\"480p\","
                + "\"sourceRevisionId\":\"" + revisionId + "\",\"idempotencyKey\":\"" + "c".repeat(64)
                + "\",\"providerTaskId\":\"" + providerTaskId + "\",\"format\":\"mp4\"},"
                + "\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"video-it\"}", runId);
    }

    private MediaControlResponse requestControl(UUID runId, UUID jobId, int attempt) throws Exception {
        String replyQueue = "provider.control.reply.it-worker." + UUID.randomUUID();
        String corrId = UUID.randomUUID().toString();
        try (var conn = rabbit.getConnectionFactory().createConnection();
                var ch = conn.createChannel(false)) {
            ch.queueDeclare(replyQueue, false, true, true, null);
            BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
            ch.basicConsume(replyQueue, true, (tag, delivery) -> {
                if (corrId.equals(delivery.getProperties().getCorrelationId())) {
                    inbox.offer(delivery.getBody());
                }
            }, tag -> { });
            String payload = "{\"schemaVersion\":\"1\",\"runId\":\"" + runId
                    + "\",\"jobId\":\"" + jobId + "\",\"attempt\":" + attempt + "}";
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .replyTo(replyQueue)
                    .correlationId(corrId)
                    .contentType("application/json")
                    .build();
            ch.basicPublish("provider", "control", props, payload.getBytes(StandardCharsets.UTF_8));
            byte[] reply = inbox.poll(10, TimeUnit.SECONDS);
            assertThat(reply).as("control reply for step " + jobId).isNotNull();
            return JSON.readValue(reply, MediaControlResponse.class);
        }
    }

    private UUID uploadProductImage(UserView owner, String token) {
        ResponseEntity<UploadResponseView> grant = post("/api/v1/uploads", token,
                new UploadRequestView("product.png", (long) ImageFixtures.png().length), UploadResponseView.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UploadResponseView body = grant.getBody();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(owner.id() + "/" + body.uploadId())
                    .contentType("image/png"), RequestBody.fromBytes(ImageFixtures.png()));
        }
        return body.uploadId();
    }

    private void putMediaObject(String key, String contentType, byte[] data) {
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(MEDIA_BUCKET).key(key).contentType(contentType),
                    RequestBody.fromBytes(data));
        }
    }

    private Message receiveQueueJobFor(UUID runId, String queueName, String capability, Duration window) {
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive(queueName, 200);
            if (message == null) {
                continue;
            }
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (body.contains(runId.toString()) && body.contains("\"" + capability + "\"")) {
                return message;
            }
        }
        return null;
    }

    private void publishResult(String exchange, String jsonBody, UUID runId) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setCorrelationId(runId.toString());
        rabbit.send(exchange, "result", new Message(jsonBody.getBytes(StandardCharsets.UTF_8), properties));
    }

    record ClipJob(UUID jobId, int stepOrder) {
    }

    record ParallelRun(UUID runId, UUID ownerId, String token, UUID revisionId, ClipJob clip7, ClipJob clip8) {
    }

    record ApproveScriptView(UUID revisionId, Long expectedVersion) {
    }

    record ProductVideoView(String productName, String description, String cta, List<UUID> uploadIds,
            VideoConfigView video, String templateId, String mcPresetId) {

        record VideoConfigView(String aspectRatio) {
        }
    }

    record UploadRequestView(String fileName, Long size) {
    }

    record UploadResponseView(UUID uploadId) {
    }

    record ReviewTaskResponseView(UUID id, UUID currentRevisionId, Long version, String status) {
    }
}
