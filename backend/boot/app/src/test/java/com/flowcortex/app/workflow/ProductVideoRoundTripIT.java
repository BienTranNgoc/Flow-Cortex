package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.user.api.UserView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.product-video.enabled=true"
})
@AutoConfigureTestRestTemplate
class ProductVideoRoundTripIT extends AbstractWorkflowHttpIT {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @MockitoSpyBean
    private MediaJobs mediaJobs;

    @BeforeEach
    void prepareQueues() {
        org.mockito.Mockito.when(mediaJobs.isEnabled()).thenReturn(true);
        for (String queue : List.of("provider.jobs", "provider.video.jobs", "media.jobs")) {
            amqpAdmin.purgeQueue(queue, false);
        }
    }

    @Test
    void fixtureArtifactsCarryAProductVideoThroughTwoGeneratedClips() throws Exception {
        UserView owner = newUser("product-round-trip");
        String token = loginAs(owner);
        List<UUID> uploads = List.of(uploadImage(owner), uploadImage(owner));

        ResponseEntity<RunResponseView> started = post("/api/v1/product-videos/runs", token,
                new ProductVideoView("Bình giữ nhiệt", "Giữ đồ uống tiện lợi", "Đặt mua ngay", uploads,
                        new VideoConfigView("9:16")),
                RunResponseView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = started.getBody().id();

        JsonNode scriptJob = receiveJob("provider.jobs", runId, "generate_script");
        assertThat(scriptJob.path("inputs").get(0).path("key").asString()).isEqualTo("image_1");
        String productImageKey = scriptJob.path("inputs").get(0).path("objectKey").asString();
        publishResult("provider", runId, scriptJob, null,
                "{\"profile\":\"product_ad_v1\",\"language\":\"vi\",\"script\":\"Đặt mua ngay\"}");
        pollUntil(owner.id(), runId, run -> "waiting_approval".equals(run.status()), WAIT);

        ReviewTaskView review = get("/api/v1/runs/" + runId + "/review-tasks", token,
                ReviewTaskView[].class).getBody()[0];
        ResponseEntity<String> approved = post("/api/v1/review-tasks/" + review.id() + "/approve", token,
                new ApprovalView(review.currentRevisionId(), review.version()), String.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode storyboardJob = receiveJob("provider.jobs", runId, "generate_storyboard");
        assertThat(storyboardJob.path("params").path("payload").path("sourceRevisionId").asString())
                .isEqualTo(review.currentRevisionId().toString());
        publishResult("provider", runId, storyboardJob, null,
                "{\"profile\":\"product_ad_storyboard_v1\","
                        + "\"sourceRevisionId\":\"" + review.currentRevisionId() + "\","
                        + "\"scene1Prompt\":\"Opening hero scene\","
                        + "\"scene2Prompt\":\"Benefit closing scene\"}");

        JsonNode speechJob = receiveJob("provider.jobs", runId, "generate_speech");
        assertThat(speechJob.path("params").path("payload").path("sourceRevisionId").asString())
                .isEqualTo(review.currentRevisionId().toString());
        String speechKey = outputKey(speechJob, "speech.wav");
        String manifestKey = outputKey(speechJob, "manifest.json");
        put(MEDIA_BUCKET, speechKey, "audio/wav", "valid fixture wav");
        put(MEDIA_BUCKET, manifestKey, "application/json", "{}");
        publishResult("provider", runId, speechJob,
                output("speech.wav", speechKey, "audio/wav", 17) + ","
                        + output("manifest.json", manifestKey, "application/json", 2),
                "{\"profile\":\"vi_voice_v1\",\"durationMs\":9000,"
                        + "\"provider\":\"fixture\",\"voice\":\"silence\",\"speed\":\"+0%\","
                        + "\"sourceRevisionId\":\"" + review.currentRevisionId() + "\","
                        + "\"idempotencyKey\":\"" + "a".repeat(64) + "\",\"format\":\"wav\"}");

        JsonNode firstVideoJob = receiveJob("provider.video.jobs", runId, "generate_video");
        assertThat(firstVideoJob.path("params").path("payload").path("prompt").asString())
                .isEqualTo("Opening hero scene");
        assertSendsFirstProductImage(firstVideoJob, runId);
        String firstClipKey = outputKey(firstVideoJob, "clip.mp4");
        put(MEDIA_BUCKET, firstClipKey, "video/mp4", "fixture clip one");
        publishVideoResult(runId, firstVideoJob, firstClipKey, "b");

        JsonNode secondVideoJob = receiveJob("provider.video.jobs", runId, "generate_video");
        assertThat(secondVideoJob.path("params").path("payload").path("prompt").asString())
                .isEqualTo("Benefit closing scene");
        assertSendsFirstProductImage(secondVideoJob, runId);
        String secondClipKey = outputKey(secondVideoJob, "clip.mp4");
        put(MEDIA_BUCKET, secondClipKey, "video/mp4", "fixture clip two");
        publishVideoResult(runId, secondVideoJob, secondClipKey, "c");
        // Every provider job of the run was taken off its own queue; nothing went to the wrong one.
        assertThat(rabbit.receive("provider.jobs", 200)).isNull();

        JsonNode renderJob = receiveJob("media.jobs", runId, "render_video");
        JsonNode renderInputs = renderJob.get("inputs");
        assertThat(List.of(renderInputs.get(0).get("key").asString(), renderInputs.get(1).get("key").asString(),
                renderInputs.get(2).get("key").asString(), renderInputs.get(3).get("key").asString(),
                renderInputs.get(4).get("key").asString()))
                .containsExactly("image_1", "image_2", "speech", "generated_video_1", "generated_video_2");
        assertThat(renderJob.get("inputs").get(2).get("objectKey").asString()).isEqualTo(speechKey);
        assertThat(renderJob.get("inputs").get(3).get("objectKey").asString()).isEqualTo(firstClipKey);
        assertThat(renderJob.get("inputs").get(4).get("objectKey").asString()).isEqualTo(secondClipKey);

        String videoKey = outputKey(renderJob, "video.mp4");
        put(MEDIA_BUCKET, videoKey, "video/mp4", "fixture mp4");
        publishResult("media", runId, renderJob, output("video.mp4", videoKey, "video/mp4", 11),
                "{\"profile\":\"vertical_ad_v1\",\"aspectRatio\":\"9:16\",\"durationSeconds\":10}");

        assertThat(pollUntil(owner.id(), runId, run -> "completed".equals(run.status()), WAIT).steps())
                .allMatch(step -> "completed".equals(step.status()));
        assertThat(get("/api/v1/runs/" + runId + "/steps/7/outputs/video.mp4/url", token, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static void assertSendsFirstProductImage(JsonNode videoJob, UUID runId) {
        JsonNode inputs = videoJob.get("inputs");
        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0).get("key").asString()).isEqualTo("image_1");
        assertThat(inputs.get(0).get("bucket").asString()).isEqualTo(MEDIA_BUCKET);
        assertThat(inputs.get(0).get("objectKey").asString()).isEqualTo("runs/" + runId + "/inputs/image_1");
    }

    private void publishVideoResult(UUID runId, JsonNode job, String clipKey, String hashCharacter) {
        publishResult("provider", runId, job,
                output("clip.mp4", clipKey, "video/mp4", 16),
                "{\"profile\":\"vertical_ad_clip_v1\",\"durationSeconds\":5,"
                        + "\"provider\":\"wan\",\"model\":\"wan-video/wan-2.2-5b-fast\","
                        + "\"aspectRatio\":\"9:16\",\"resolution\":\"480p\","
                        + "\"sourceRevisionId\":\"" + job.path("params").path("payload")
                                .path("sourceRevisionId").asString() + "\","
                        + "\"idempotencyKey\":\"" + hashCharacter.repeat(64) + "\","
                        + "\"providerTaskId\":\"prediction-" + job.get("stepOrder").asInt() + "\","
                        + "\"format\":\"mp4\"}");
    }

    private JsonNode receiveJob(String queue, UUID runId, String capability) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive(queue, 500);
            if (message != null) {
                JsonNode body = JSON.readTree(message.getBody());
                if (runId.toString().equals(body.get("runId").asString())
                        && capability.equals(body.get("capability").asString())) {
                    assertThat(message.getMessageProperties().getMessageId())
                            .isEqualTo(body.get("jobId").asString());
                    return body;
                }
            }
        }
        throw new AssertionError("No " + capability + " job reached " + queue);
    }

    private void publishResult(String exchange, UUID runId, JsonNode job, String outputs, String data) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setCorrelationId(runId.toString());
        String body = "{\"schemaVersion\":\"1\",\"jobId\":\"" + job.get("jobId").asString()
                + "\",\"runId\":\"" + runId + "\",\"attempt\":1,\"deliveryCount\":0,"
                + "\"status\":\"completed\"," + (outputs == null ? "" : "\"outputs\":[" + outputs + "],")
                + "\"data\":" + data + ",\"finishedAt\":\"" + Instant.now()
                + "\",\"workerId\":\"fixture-worker\"}";
        rabbit.send(exchange, "result", new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private static String output(String name, String key, String mime, int bytes) {
        return "{\"name\":\"" + name + "\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\""
                + key + "\",\"mime\":\"" + mime + "\",\"bytes\":" + bytes + "}";
    }

    private static String outputKey(JsonNode job, String name) {
        return job.get("outputPrefix").asString() + name;
    }

    private UUID uploadImage(UserView owner) {
        String token = loginAs(owner);
        ResponseEntity<UploadView> grant = post("/api/v1/uploads", token,
                new UploadRequestView("product.png", 16L), UploadView.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID uploadId = grant.getBody().uploadId();
        put(UPLOAD_BUCKET, owner.id() + "/" + uploadId, "image/png", "sixteen bytes!!!");
        return uploadId;
    }

    private static void put(String bucket, String key, String mime, String body) {
        try (S3Client store = objectStore()) {
            store.putObject(request -> request.bucket(bucket).key(key).contentType(mime),
                    RequestBody.fromBytes(body.getBytes(StandardCharsets.UTF_8)));
        }
    }

    record ProductVideoView(String productName, String description, String cta, List<UUID> uploadIds,
            VideoConfigView video) {
    }

    record VideoConfigView(String aspectRatio) {
    }

    record UploadRequestView(String fileName, Long size) {
    }

    record UploadView(UUID uploadId) {
    }

    record ReviewTaskView(UUID id, Long version, UUID currentRevisionId) {
    }

    record ApprovalView(UUID revisionId, Long expectedVersion) {
    }
}
