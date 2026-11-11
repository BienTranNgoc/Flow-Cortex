package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code GET /api/v1/runs/{id}/logs} and {@code .../outputs/{name}/peaks} over HTTP: the run's step states
 * and its stored sounds are written straight to the database and the media bucket, since only the reading is
 * under test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RunDiagnosticsEndpointsIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    record StartUploadRunView(UUID uploadId, String originalName) {
    }

    record StepView(UUID id, int stepOrder) {
    }

    record StartedRunView(UUID id, List<StepView> steps) {
    }

    record EntryView(int stepOrder, String level, String status, String message, String errorCode) {
    }

    record SummaryView(int errorCount, int warningCount, String firstError) {
    }

    record LogView(String status, String traceId, int stepCount, List<EntryView> entries, SummaryView summary) {
    }

    record PeaksView(List<Double> peaks) {
    }

    record StartedRun(UUID runId, String token, UUID userId, ResponseEntity<StartedRunView> response) {
        String logPath() {
            return "/api/v1/runs/" + runId + "/logs";
        }

        String peaksPath(int stepOrder, String name) {
            return "/api/v1/runs/" + runId + "/steps/" + stepOrder + "/outputs/" + name + "/peaks";
        }
    }

    @Test
    void logOfAFailedRunPutsTheErrorFirst() {
        StartedRun run = startRun("log-failed", "probe", "echo", "echo");
        jdbc.update("UPDATE run_step SET status = 'completed', started_at = now(), finished_at = now() "
                + "WHERE run_id = ? AND step_order = 1", run.runId());
        jdbc.update("UPDATE run_step SET status = 'error', error_code = 'media.timeout', "
                + "error_message = 'Worker timed out', started_at = now(), finished_at = now() "
                + "WHERE run_id = ? AND step_order = 2", run.runId());
        jdbc.update("UPDATE run_step SET status = 'pending' WHERE run_id = ? AND step_order = 3", run.runId());

        ResponseEntity<LogView> response = get(run.logPath(), run.token(), LogView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        LogView log = response.getBody();
        assertThat(log.stepCount()).isEqualTo(3);
        assertThat(log.entries()).extracting(EntryView::stepOrder).containsExactly(2, 1, 3);
        EntryView first = log.entries().getFirst();
        assertThat(first.level()).isEqualTo("error");
        assertThat(first.errorCode()).isEqualTo("media.timeout");
        assertThat(first.message()).isEqualTo("Worker timed out");
        assertThat(log.summary()).isEqualTo(new SummaryView(1, 0, "Worker timed out"));

        ResponseEntity<LogView> errorsOnly = get(run.logPath() + "?level=error&limit=1", run.token(), LogView.class);
        assertThat(errorsOnly.getBody().entries()).extracting(EntryView::stepOrder).containsExactly(2);
        assertThat(errorsOnly.getBody().stepCount()).isEqualTo(3);
    }

    @Test
    void aLevelOrLimitOutsideItsRangeIsRejectedNamingIt() {
        StartedRun run = startRun("log-invalid", "probe");

        for (String query : List.of("?level=debug", "?limit=0", "?limit=501", "?limit=many")) {
            ResponseEntity<String> response = get(run.logPath() + query, run.token(), String.class);

            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).as(query).containsAnyOf("level", "limit");
        }
    }

    @Test
    void anotherMembersLogIs404() {
        StartedRun run = startRun("log-foreign", "probe");
        assertThat(get(run.logPath(), run.token(), LogView.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String intruder = loginAs(newUser("log-intruder"));

        ResponseEntity<String> response = get(run.logPath(), intruder, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(get(run.logPath(), null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aDeletedRunsLogIs404() {
        StartedRun run = startRun("log-deleted", "probe");
        assertThat(get(run.logPath(), run.token(), LogView.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE workflow_run SET deleted_at = now() WHERE id = ?", run.runId());

        assertThat(get(run.logPath(), run.token(), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aRunStartedThroughTheApiCarriesTheResponseTraceId() {
        StartedRun run = startRun("log-trace", "probe");
        String responseTraceId = run.response().getHeaders().getFirst("X-Trace-Id");

        LogView log = get(run.logPath(), run.token(), LogView.class).getBody();

        assertThat(responseTraceId).matches("[0-9a-f]{32}");
        assertThat(log.traceId()).isEqualTo(responseTraceId);
    }

    @Test
    void peaksOfAStoredWavAreOneHundredTwentyValuesAndStored() throws IOException {
        StartedRun run = startRun("peaks-wav", "probe");
        String key = storeOutput(run, "source-audio.wav", "audio/wav", loudEndWav());

        ResponseEntity<PeaksView> first = get(run.peaksPath(1, "source-audio.wav"), run.token(), PeaksView.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Double> peaks = first.getBody().peaks();
        assertThat(peaks).hasSize(120).allMatch(value -> value >= 0 && value <= 1);
        assertThat(peaks.subList(0, 60)).containsOnly(0.5);
        assertThat(peaks.subList(60, 120)).containsOnly(1.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_step_audio_peaks WHERE output_name = ? "
                + "AND run_step_id = (SELECT id FROM run_step WHERE run_id = ? AND step_order = 1)", Integer.class,
                "source-audio.wav", run.runId())).isEqualTo(1);

        try (S3Client store = objectStore()) {
            store.deleteObject(o -> o.bucket(MEDIA_BUCKET).key(key));
        }
        ResponseEntity<PeaksView> second = get(run.peaksPath(1, "source-audio.wav"), run.token(), PeaksView.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().peaks()).isEqualTo(peaks);
    }

    @Test
    void bytesThatAreNotAWavAnswerNullPeaksAndAreRemembered() {
        StartedRun run = startRun("peaks-null", "probe");
        storeOutput(run, "broken.wav", "audio/wav", "not a wav".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<PeaksView> response = get(run.peaksPath(1, "broken.wav"), run.token(), PeaksView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().peaks()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_step_audio_peaks WHERE output_name = 'broken.wav' "
                + "AND peaks IS NULL AND run_step_id = (SELECT id FROM run_step WHERE run_id = ? AND step_order = 1)",
                Integer.class, run.runId())).isEqualTo(1);
    }

    @Test
    void peaksOfAVideoOutputAre422() {
        StartedRun run = startRun("peaks-video", "probe");
        storeOutput(run, "ad.mp4", "video/mp4", new byte[] {0, 0, 0, 24});

        ResponseEntity<String> response = get(run.peaksPath(1, "ad.mp4"), run.token(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("not a sound");
    }

    @Test
    void anotherMembersPeaksAndAnUnknownOutputAre404() throws IOException {
        StartedRun run = startRun("peaks-foreign", "probe");
        storeOutput(run, "source-audio.wav", "audio/wav", loudEndWav());
        assertThat(get(run.peaksPath(1, "source-audio.wav"), run.token(), PeaksView.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String intruder = loginAs(newUser("peaks-intruder"));

        assertThat(get(run.peaksPath(1, "source-audio.wav"), intruder, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(run.peaksPath(1, "missing.wav"), run.token(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(run.peaksPath(9, "source-audio.wav"), run.token(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Starts a run of {@code stepTypes} through the API for a new member; its first step is a media step. */
    private StartedRun startRun(String prefix, String... stepTypes) {
        UserView user = newUser(prefix);
        NewWorkflowStep[] steps = new NewWorkflowStep[stepTypes.length];
        for (int i = 0; i < stepTypes.length; i++) {
            steps[i] = new NewWorkflowStep("Step " + (i + 1), stepTypes[i], null);
        }
        WorkflowView workflow = newWorkflow(user.id(), false, steps);
        UUID uploadId = UUID.randomUUID();
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key(user.id() + "/" + uploadId).contentType("video/mp4"),
                    RequestBody.fromBytes("a one-second clip".getBytes(StandardCharsets.UTF_8)));
        }
        String token = loginAs(user);
        ResponseEntity<StartedRunView> response = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartUploadRunView(uploadId, "clip.mp4"), StartedRunView.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return new StartedRun(response.getBody().id(), token, user.id(), response);
    }

    /** Writes {@code bytes} under step 1's attempt prefix of the media bucket and records it as that step's output. */
    private String storeOutput(StartedRun run, String name, String mime, byte[] bytes) {
        String key = "runs/" + run.runId() + "/steps/1/attempts/1/" + name;
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(MEDIA_BUCKET).key(key).contentType(mime), RequestBody.fromBytes(bytes));
        }
        jdbc.update("UPDATE run_step SET outputs = ?::jsonb WHERE run_id = ? AND step_order = 1",
                "[{\"name\":\"" + name + "\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\"" + key
                        + "\",\"mime\":\"" + mime + "\",\"bytes\":" + bytes.length + "}]",
                run.runId());
        return key;
    }

    /** 16-bit mono, 1200 samples: the first 600 at amplitude 8192, the last 600 at 16384. */
    private static byte[] loudEndWav() throws IOException {
        byte[] pcm = new byte[2400];
        for (int i = 0; i < 1200; i++) {
            int sample = i < 600 ? 8192 : 16384;
            pcm[2 * i] = (byte) (sample & 0xff);
            pcm[2 * i + 1] = (byte) ((sample >> 8) & 0xff);
        }
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 8000f, 16, 1, 2, 8000f, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, 1200), AudioFileFormat.Type.WAVE,
                out);
        return out.toByteArray();
    }
}
