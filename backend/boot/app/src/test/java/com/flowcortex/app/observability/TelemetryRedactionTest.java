package com.flowcortex.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Presigned URLs carry a credential and a signature in their query. Whatever the backend records
 * about a URL in telemetry keeps only its path, and its logs name objects by bucket and key.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TelemetryRedactionTest extends AbstractWorkflowHttpIT {

    private static final String SIGNED = "http://minio:9000/flowcortex-media/runs/r/steps/1/video-details.json"
            + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AK%2F20261001%2Fus-east-1%2Fs3%2Faws4_request"
            + "&X-Amz-Signature=abc";
    private static final String BARE = "http://minio:9000/flowcortex-media/runs/r/steps/1/video-details.json";

    @Autowired
    private ObservationRegistry observations;

    @Autowired
    private JdbcTemplate jdbc;

    record UploadRequestView(String fileName, Long size) {
    }

    record UploadResponseView(UUID uploadId, String url) {
    }

    record StartUploadRunView(UUID uploadId, String originalName) {
    }

    record StepView(UUID id) {
    }

    record StartedRunView(UUID id, List<StepView> steps) {
    }

    record OutputUrlView(String url) {
    }

    /** Collects every stopped observation named {@code name}. */
    private List<Observation.Context> stoppedObservations(String name) {
        List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return name.equals(context.getName());
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
        return stopped;
    }

    @Test
    void aSigningValueInsideAnyOtherValueIsReplaced() {
        List<Observation.Context> stopped = stoppedObservations("test.redaction.embedded");

        Observation.createNotStarted("test.redaction.embedded", observations)
                .lowCardinalityKeyValue("error", "GET " + SIGNED + " failed")
                .highCardinalityKeyValue("headers",
                        "X-Amz-Security-Token=tok%2Fen&X-Amz-Credential=AK%2Fx \"X-Amz-Signature=abc\"")
                .highCardinalityKeyValue("unrelated", "X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .start()
                .stop();

        assertThat(stopped).hasSize(1);
        Observation.Context context = stopped.getFirst();
        assertThat(value(context, "error")).isEqualTo("GET " + BARE + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=REDACTED&X-Amz-Signature=REDACTED failed");
        assertThat(value(context, "headers")).isEqualTo(
                "X-Amz-Security-Token=REDACTED&X-Amz-Credential=REDACTED \"X-Amz-Signature=REDACTED\"");
        assertThat(value(context, "unrelated")).isEqualTo("X-Amz-Algorithm=AWS4-HMAC-SHA256");
    }

    @Test
    void aSignedUrlRecordedOnAnObservationKeepsOnlyItsPath() {
        List<Observation.Context> stopped = stoppedObservations("test.redaction");

        Observation.createNotStarted("test.redaction", observations)
                .lowCardinalityKeyValue("url.full", SIGNED)
                .highCardinalityKeyValue("http.url", SIGNED)
                .highCardinalityKeyValue("http.target", "/flowcortex-media/k?X-Amz-Signature=abc")
                .highCardinalityKeyValue("unrelated", "kept")
                .start()
                .stop();

        assertThat(stopped).hasSize(1);
        Observation.Context context = stopped.getFirst();
        assertThat(value(context, "url.full")).isEqualTo(BARE);
        assertThat(value(context, "http.url")).isEqualTo(BARE);
        assertThat(value(context, "http.target")).isEqualTo("/flowcortex-media/k");
        assertThat(value(context, "unrelated")).isEqualTo("kept");
    }

    @Test
    void theLogsOfAnUploadGrantAndADownloadUrlNameTheObjectButNeverTheSignedUrl(CapturedOutput output) {
        UserView user = newUser("redaction-logs");
        String token = loginAs(user);

        ResponseEntity<UploadResponseView> grant = post("/api/v1/uploads", token,
                new UploadRequestView("clip.mp4", 16L), UploadResponseView.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(grant.getBody().url()).contains("X-Amz-Signature=");

        UUID runId = completedRunWithReport(user, token);
        ResponseEntity<OutputUrlView> download = get("/api/v1/runs/" + runId
                + "/steps/1/outputs/video-details.json/url", token, OutputUrlView.class);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getBody().url()).contains("X-Amz-Signature=");

        assertThat(output.getAll())
                .contains("Upload grant issued: bucket=" + UPLOAD_BUCKET + " key=" + user.id() + "/"
                        + grant.getBody().uploadId())
                .contains("Download URL issued: bucket=" + MEDIA_BUCKET + " key=runs/" + runId
                        + "/steps/1/video-details.json")
                .doesNotContain("X-Amz-");
    }

    /** A probe run whose step is marked completed with one stored report; no worker is involved. */
    private UUID completedRunWithReport(UserView user, String token) {
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("Inspect the file", "probe", null));
        UUID uploadId = UUID.randomUUID();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(user.id() + "/" + uploadId).contentType("video/mp4"),
                    RequestBody.fromBytes("a one-second clip".getBytes(StandardCharsets.UTF_8)));
        }
        StartedRunView run = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartUploadRunView(uploadId, "clip.mp4"), StartedRunView.class).getBody();
        String key = "runs/" + run.id() + "/steps/1/video-details.json";
        jdbc.update("UPDATE run_step SET status = 'completed', outputs = ?::jsonb WHERE id = ?",
                "[{\"name\":\"video-details.json\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\"" + key
                        + "\",\"mime\":\"application/json\",\"bytes\":2}]",
                run.steps().getFirst().id());
        return run.id();
    }

    private static String value(Observation.Context context, String key) {
        Map<String, String> values = new HashMap<>();
        for (KeyValue keyValue : context.getAllKeyValues()) {
            values.put(keyValue.getKey(), keyValue.getValue());
        }
        return values.get(key);
    }
}
