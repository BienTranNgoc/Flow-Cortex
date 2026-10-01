package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code flowcortex.media.enabled=false}: a run of a workflow with a media step, and an upload URL,
 * are refused as a 503 Problem Detail and nothing is written, while inline-only workflows still start
 * and the outputs of finished runs stay downloadable. Dedicated context, since the property binds
 * once at startup.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.media.enabled=false")
@AutoConfigureTestRestTemplate
class MediaKillSwitchIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startingARunWithAMediaStepReturns503AndCreatesNoRun() {
        UserView user = newUser("media-killswitch");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("check", "probe", null));
        String token = loginAs(user);

        ResponseEntity<String> response = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartRunRequestView("{}"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(response.getBody()).contains("Media engine is currently disabled");
        assertThat(countRunsFor(workflow.id())).isZero();
    }

    @Test
    void requestingAnUploadUrlReturns503() {
        String token = loginAs(newUser("media-killswitch-upload"));

        ResponseEntity<String> response = post("/api/v1/uploads", token, Map.of("fileName", "clip.mp4", "size", 16),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(response.getBody()).contains("Media engine is currently disabled").doesNotContain("X-Amz-");
    }

    @Test
    void anInlineOnlyWorkflowStillStarts() {
        UserView user = newUser("media-killswitch-inline");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(user);

        ResponseEntity<RunResponseView> response = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartRunRequestView("hello"), RunResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aFinishedRunsOutputStaysDownloadable() throws Exception {
        UserView user = newUser("media-killswitch-download");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(user);
        UUID runId = post("/api/v1/workflows/" + workflow.id() + "/runs", token, new StartRunRequestView("hello"),
                RunResponseView.class).getBody().id();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_run WHERE id = ?", String.class, runId)).isEqualTo("completed"));
        // No remote step can run while the switch is off, so the stored output of an earlier
        // probe run is put in place directly.
        String key = "runs/" + runId + "/steps/1/video-details.json";
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(MEDIA_BUCKET).key(key).contentType("application/json"),
                    RequestBody.fromString("{}"));
        }
        jdbcTemplate.update("UPDATE run_step SET outputs = ?::jsonb WHERE run_id = ? AND step_order = 1",
                "[{\"name\":\"video-details.json\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\"" + key
                        + "\",\"mime\":\"application/json\",\"bytes\":2}]",
                runId);

        ResponseEntity<OutputUrlView> response = get("/api/v1/runs/" + runId
                + "/steps/1/outputs/video-details.json/url", token, OutputUrlView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> download = client.send(HttpRequest.newBuilder(URI.create(response.getBody().url()))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo("{}");
        }
    }

    record OutputUrlView(String url) {
    }

    private Long countRunsFor(UUID workflowId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                workflowId);
    }
}
