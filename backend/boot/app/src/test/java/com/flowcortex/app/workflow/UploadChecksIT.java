package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Per-kind size caps at the upload grant, the content check at run start, and the readiness refusal
 * of the seeded incomplete demo workflow. The dev profile is active so the demo seeder runs. A media
 * run started without any upload is covered by {@code UploadsEndpointIT
 * #aMediaRunStartedWithoutAnUploadIsRefusedOnTheUploadFieldAndCreatesNoRun}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.media.upload.content-check.enabled=true")
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class UploadChecksIT extends AbstractWorkflowHttpIT {

    private static final long AUDIO_CAP = 104_857_600L;
    private static final long VIDEO_CAP = 524_288_000L;
    private static final byte[] MP4_HEADER = {0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
        0x00, 0x00, 0x02, 0x00};

    @Autowired
    private JdbcTemplate jdbc;

    record UploadRequestView(String fileName, Long size) {
    }

    record UploadGrantView(UUID uploadId, long maxBytes) {
    }

    record ProblemView(String detail, String code, Map<String, String> errors) {
    }

    @Test
    void anAudioFileExactlyAtItsCapIsGrantedAndOneByteMoreIsRefusedNamingTheCapAndTheFormats() {
        String token = loginAs(newUser("upload-cap-audio"));

        ResponseEntity<UploadGrantView> atCap = post("/api/v1/uploads", token,
                new UploadRequestView("voice.m4a", AUDIO_CAP), UploadGrantView.class);
        assertThat(atCap.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(atCap.getBody().maxBytes()).isEqualTo(AUDIO_CAP);

        ResponseEntity<ProblemView> overCap = post("/api/v1/uploads", token,
                new UploadRequestView("voice.m4a", AUDIO_CAP + 1), ProblemView.class);
        assertThat(overCap.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overCap.getBody().errors()).containsEntry("size",
                "Files of this type must be at most 100 MB. Accepted: .mp3, .wav, .m4a.");
    }

    @Test
    void aVideoOfFiveHundredMegabytesIsGranted() {
        ResponseEntity<UploadGrantView> response = post("/api/v1/uploads", loginAs(newUser("upload-cap-video")),
                new UploadRequestView("clip.mp4", VIDEO_CAP), UploadGrantView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().maxBytes()).isEqualTo(VIDEO_CAP);
    }

    @Test
    void aTextFileRenamedToMp4IsRefusedAtRunStartAndCreatesNoRun() {
        UserView user = newUser("upload-sniff-text");
        String token = loginAs(user);
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("Inspect", "probe", null));
        byte[] text = "These are my meeting notes.".getBytes(StandardCharsets.UTF_8);
        UUID uploadId = uploaded(user, token, "notes.mp4", text);

        ResponseEntity<ProblemView> response = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                Map.of("uploadId", uploadId, "originalName", "notes.mp4"), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors()).containsEntry("uploadId",
                "This file isn't a valid video file. Accepted: .mp4, .mov, .webm, .mkv.");
        assertThat(runsOf(workflow)).isZero();
    }

    @Test
    void aRealMp4HeaderPassesTheContentCheckAndTheRunIsCreated() {
        UserView user = newUser("upload-sniff-mp4");
        String token = loginAs(user);
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("Inspect", "probe", null));
        UUID uploadId = uploaded(user, token, "clip.mp4", MP4_HEADER);

        ResponseEntity<String> response = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                Map.of("uploadId", uploadId, "originalName", "clip.mp4"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runsOf(workflow)).isEqualTo(1L);
    }

    @Test
    void theSeededIncompleteDemoIsRefusedWithTheFailingStepAndCreatesNoRun() {
        UserView user = newUser("readiness-demo");
        WorkflowView demo = workflowService.listVisibleWorkflows(user.id()).stream()
                .filter(workflow -> workflow.name().equals("Incomplete Demo (step 2 not configured)"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Incomplete demo workflow not seeded"));

        ResponseEntity<ProblemView> response = post("/api/v1/workflows/" + demo.id() + "/runs", loginAs(user),
                Map.of("input", "hello"), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("WORKFLOW_INCOMPLETE");
        assertThat(response.getBody().detail())
                .isEqualTo("This workflow can't run yet: step 2 is not fully configured.");
        assertThat(response.getBody().errors())
                .containsExactly(Map.entry("step2", "Reads from step 5, which is not an earlier step"));
        assertThat(runsOf(demo)).isZero();
    }

    /** Grants an upload for {@code fileName} and stores {@code body} at its key as the browser's PUT would. */
    private UUID uploaded(UserView user, String token, String fileName, byte[] body) {
        ResponseEntity<UploadGrantView> grant = post("/api/v1/uploads", token,
                new UploadRequestView(fileName, (long) body.length), UploadGrantView.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID uploadId = grant.getBody().uploadId();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(user.id() + "/" + uploadId).contentType("video/mp4"),
                    RequestBody.fromBytes(body));
        }
        return uploadId;
    }

    private long runsOf(WorkflowView workflow) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                workflow.id());
    }
}
