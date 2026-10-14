package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code GET /api/v1/runs/{id}/steps/{order}/outputs/{name}/url} after a probe step completed
 * through the real broker, with this test as the worker: the URL is fetched from the test JVM,
 * outside the container network, as the member's browser would.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RunOutputsEndpointIT extends AbstractWorkflowHttpIT {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String REPORT = "{\"format\":{\"format_name\":\"mov,mp4,m4a,3gp,3g2,mj2\"}}";

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private JdbcTemplate jdbc;

    record StartUploadRunView(UUID uploadId, String originalName) {
    }

    record StepView(UUID id, int stepOrder, String status) {
    }

    record StartedRunView(UUID id, List<StepView> steps) {
    }

    record OutputUrlView(String url, Instant expiresAt) {
    }

    record CompletedRun(UUID runId, String token) {
        String outputUrl(int stepOrder, String name) {
            return "/api/v1/runs/" + runId + "/steps/" + stepOrder + "/outputs/" + name + "/url";
        }
    }

    @Test
    void theOwnerGetsAPresignedGetOnThePublicEndpointThatDownloadsTheReportAsAnAttachment() throws Exception {
        CompletedRun run = completedProbeRun("outputs-owner");
        Instant before = Instant.now();

        ResponseEntity<OutputUrlView> response = get(run.outputUrl(1, "video-details.json"), run.token(),
                OutputUrlView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        OutputUrlView body = response.getBody();
        assertThat(body.expiresAt()).isBetween(before.plus(Duration.ofMinutes(14)), Instant.now().plus(Duration.ofMinutes(16)));
        URI url = URI.create(body.url());
        assertThat(url.getScheme() + "://" + url.getAuthority()).isEqualTo(publicEndpoint());
        assertThat(url.getPath()).isEqualTo("/" + MEDIA_BUCKET + "/runs/" + run.runId()
                + "/steps/1/attempts/1/video-details.json");
        assertThat(body.url()).doesNotContain(UPLOAD_BUCKET);

        HttpResponse<String> download = fetch(url);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(REPORT);
        assertThat(download.headers().firstValue("content-disposition"))
                .hasValue("attachment; filename=\"video-details.json\"");
    }

    @Test
    void anotherUserGetsNotFoundAndNoUrl() {
        CompletedRun run = completedProbeRun("outputs-foreign");
        assertOwnerGetsTheUrl(run);
        String intruder = loginAs(newUser("outputs-intruder"));

        ResponseEntity<String> response = get(run.outputUrl(1, "video-details.json"), intruder, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).doesNotContain("X-Amz-", MEDIA_BUCKET);
    }

    @Test
    void anUnknownOutputNameOrStepIsNotFound() {
        CompletedRun run = completedProbeRun("outputs-unknown");
        assertOwnerGetsTheUrl(run);

        for (String path : List.of(run.outputUrl(1, "nothing.json"), run.outputUrl(2, "video-details.json"))) {
            ResponseEntity<String> response = get(path, run.token(), String.class);

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).doesNotContain("X-Amz-");
        }
    }

    @Test
    void aStoredOutputOutsideItsStepIsNotSignedAndIsLogged(CapturedOutput output) {
        CompletedRun run = completedProbeRun("outputs-foreign-key");
        assertOwnerGetsTheUrl(run);
        UUID otherRun = UUID.randomUUID();

        for (String key : List.of("runs/" + otherRun + "/steps/1/video-details.json",
                "runs/" + run.runId() + "/steps/2/video-details.json")) {
            jdbc.update("UPDATE run_step SET outputs = ?::jsonb WHERE run_id = ? AND step_order = 1",
                    "[{\"name\":\"video-details.json\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\"" + key
                            + "\",\"mime\":\"application/json\",\"bytes\":2}]",
                    run.runId());

            ResponseEntity<String> response = get(run.outputUrl(1, "video-details.json"), run.token(), String.class);

            assertThat(response.getStatusCode()).as(key).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody()).doesNotContain("X-Amz-", key);
            assertThat(output.getAll()).contains("WARN").contains("runId=" + run.runId()).contains("key=" + key);
        }
    }

    @Test
    void anAnonymousCallerGetsNoUrl() {
        CompletedRun run = completedProbeRun("outputs-anonymous");
        assertOwnerGetsTheUrl(run);

        ResponseEntity<String> response = get(run.outputUrl(1, "video-details.json"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The refusals below mean something only once the owner's own request succeeds. */
    private void assertOwnerGetsTheUrl(CompletedRun run) {
        assertThat(get(run.outputUrl(1, "video-details.json"), run.token(), OutputUrlView.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** A one-step probe run whose step this test completed as the worker, its report stored in the media bucket. */
    private CompletedRun completedProbeRun(String prefix) {
        UserView user = newUser(prefix);
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("Inspect the file", "probe", null));
        UUID uploadId = UUID.randomUUID();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(user.id() + "/" + uploadId).contentType("video/mp4"),
                    RequestBody.fromBytes("a one-second clip".getBytes(StandardCharsets.UTF_8)));
        }
        String token = loginAs(user);
        StartedRunView started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartUploadRunView(uploadId, "clip.mp4"), StartedRunView.class).getBody();
        UUID runId = started.id();
        UUID stepId = started.steps().getFirst().id();
        drainJob(stepId);

        String reportKey = "runs/" + runId + "/steps/1/attempts/1/video-details.json";
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(MEDIA_BUCKET).key(reportKey).contentType("application/json"),
                    RequestBody.fromString(REPORT));
        }
        String result = "{\"schemaVersion\":\"1\",\"jobId\":\"" + stepId + "\",\"runId\":\"" + runId + "\","
                + "\"attempt\":1,\"deliveryCount\":0,\"status\":\"completed\",\"outputs\":[{\"name\":"
                + "\"video-details.json\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\"" + reportKey + "\","
                + "\"mime\":\"application/json\",\"bytes\":" + REPORT.length() + "}],"
                + "\"data\":{\"durationMs\":1000},\"finishedAt\":\"" + Instant.now() + "\",\"workerId\":\"test-worker\"}";
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setCorrelationId(runId.toString());
        rabbit.send("media", "result", new Message(result.getBytes(StandardCharsets.UTF_8), properties));
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM run_step WHERE id = ?", String.class, stepId)).isEqualTo("completed"));
        return new CompletedRun(runId, token);
    }

    /** Takes this step's job off the jobs queue, which also means the step is running and accepts a result. */
    private void drainJob(UUID stepId) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive("media.jobs", 1_000);
            if (message != null && stepId.toString().equals(message.getMessageProperties().getMessageId())) {
                return;
            }
        }
        throw new AssertionError("No job for step " + stepId + " reached media.jobs within " + WAIT);
    }

    private static HttpResponse<String> fetch(URI url) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
