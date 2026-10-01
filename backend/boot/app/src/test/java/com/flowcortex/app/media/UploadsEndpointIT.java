package com.flowcortex.app.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * {@code POST /api/v1/uploads} end to end: the grant it returns, and what the object store does
 * with a PUT made from the test JVM (outside the container network, like a browser). URLs live
 * 5 s here, so a PUT that outlasts its URL fits in a test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.media.upload.url-ttl=5s")
@AutoConfigureTestRestTemplate
class UploadsEndpointIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    private static final String SIZE_SENTENCE = "Files must be between 1 byte and 500 MB.";
    private static final String TYPE_SENTENCE =
            "This file type can't be checked yet. Use .mp4, .mov, .webm, .mkv, .m4a, .mp3 or .wav.";
    private static final byte[] CLIP = "sixteen bytes!!!".getBytes();

    record UploadRequestView(String fileName, Long size) {
    }

    record UploadResponseView(UUID uploadId, String url, String method, Map<String, String> headers,
            Instant expiresAt, long maxBytes) {
    }

    private ResponseEntity<UploadResponseView> grant(String token, String fileName, long size) {
        return post("/api/v1/uploads", token, new UploadRequestView(fileName, size), UploadResponseView.class);
    }

    @Test
    void anMp4GetsAPresignedPutOnThePublicEndpointForAKeyTheServerChose() {
        UserView user = newUser("uploads-grant");
        Instant before = Instant.now();

        ResponseEntity<UploadResponseView> response = grant(loginAs(user), "clip.mp4", CLIP.length);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UploadResponseView body = response.getBody();
        assertThat(body.method()).isEqualTo("PUT");
        assertThat(body.headers()).containsExactly(Map.entry("Content-Type", "video/mp4"));
        assertThat(body.maxBytes()).isEqualTo(524_288_000L);
        assertThat(body.expiresAt()).isBetween(before.plusSeconds(4), Instant.now().plusSeconds(6));
        URI url = URI.create(body.url());
        assertThat(url.getScheme() + "://" + url.getAuthority()).isEqualTo(publicEndpoint());
        assertThat(url.getPath()).isEqualTo("/" + UPLOAD_BUCKET + "/" + user.id() + "/" + body.uploadId());
    }

    @Test
    void anExtensionIsMatchedCaseInsensitively() {
        ResponseEntity<UploadResponseView> response = grant(loginAs(newUser("uploads-mov")), "IMG_0412.MOV", 16L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().headers()).containsExactly(Map.entry("Content-Type", "video/quicktime"));
    }

    @Test
    void aSizeOfNothingOrOverTheMaximumIsRefusedWithTheSizeSentence() {
        String token = loginAs(newUser("uploads-size"));
        for (long size : new long[] {0L, 600L * 1024 * 1024}) {
            ResponseEntity<String> response = post("/api/v1/uploads", token, new UploadRequestView("clip.mp4", size),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody()).contains("\"size\":\"" + SIZE_SENTENCE + "\"").doesNotContain("X-Amz-");
        }
    }

    @Test
    void aFileTypeOutsideTheAllowlistIsRefusedWithTheTypeSentence() {
        ResponseEntity<String> response = post("/api/v1/uploads", loginAs(newUser("uploads-type")),
                new UploadRequestView("notes.txt", 16L), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"fileName\":\"" + TYPE_SENTENCE + "\"");
    }

    @Test
    void anAnonymousCallerGetsNoUploadUrl() {
        ResponseEntity<String> response = post("/api/v1/uploads", null, new UploadRequestView("clip.mp4", 16L),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aPutWithExactlyTheReturnedHeadersStoresTheObjectUnderTheCallersKey() throws IOException {
        UserView user = newUser("uploads-put");
        UploadResponseView grant = grant(loginAs(user), "clip.mp4", CLIP.length).getBody();

        assertThat(put(grant, grant.headers().get("Content-Type"), CLIP.length, CLIP.length, null)).isEqualTo(200);

        try (S3Client store = objectStore()) {
            HeadObjectResponse head = store.headObject(o -> o.bucket(UPLOAD_BUCKET).key(user.id() + "/" + grant.uploadId()));
            assertThat(head.contentLength()).isEqualTo(CLIP.length);
            assertThat(head.contentType()).isEqualTo("video/mp4");
        }
    }

    @Test
    void aPutWithAnotherContentTypeIsRefusedAndStoresNothing() throws IOException {
        UserView user = newUser("uploads-put-type");
        UploadResponseView grant = grant(loginAs(user), "clip.mp4", CLIP.length).getBody();

        assertThat(put(grant, "text/plain", CLIP.length, CLIP.length, null)).isEqualTo(403);
        assertThat(stored(user, grant)).isFalse();
    }

    @Test
    void aPutWithAnotherByteCountIsRefusedBecauseTheByteCountIsSigned() throws IOException {
        UserView user = newUser("uploads-put-length");
        UploadResponseView grant = grant(loginAs(user), "clip.mp4", CLIP.length).getBody();

        assertThat(put(grant, "video/mp4", CLIP.length - 1, CLIP.length - 1, null)).isEqualTo(403);
        assertThat(stored(user, grant)).isFalse();
    }

    @Test
    void aPutThatStartsBeforeTheUrlExpiresAndEndsAfterIt() throws IOException {
        UserView user = newUser("uploads-put-slow");
        UploadResponseView grant = grant(loginAs(user), "clip.mp4", CLIP.length).getBody();

        int status = put(grant, "video/mp4", CLIP.length, CLIP.length / 2, grant.expiresAt().plusSeconds(2));

        assertThat(status).isEqualTo(200);
        assertThat(stored(user, grant)).isTrue();
    }

    @Test
    void aPutThatStartsAfterTheUrlExpiredIsRefusedAndStoresNothing() throws IOException {
        UserView user = newUser("uploads-put-expired");
        UploadResponseView grant = grant(loginAs(user), "clip.mp4", CLIP.length).getBody();
        sleepUntil(grant.expiresAt().plusSeconds(1));

        assertThat(put(grant, "video/mp4", CLIP.length, CLIP.length, null)).isEqualTo(403);
        assertThat(stored(user, grant)).isFalse();
    }

    @Test
    void aMediaRunStartedWithoutAnUploadIsRefusedOnTheUploadFieldAndCreatesNoRun() {
        UserView user = newUser("uploads-run-no-upload");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("Inspect", "probe", null));

        ResponseEntity<String> response = post("/api/v1/workflows/" + workflow.id() + "/runs", loginAs(user),
                Map.of("originalName", "clip.mp4"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains("\"uploadId\":\"Choose a video or audio file to upload\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                workflow.id())).isZero();
    }

    /**
     * PUTs {@code length} bytes as the browser would (no {@code Authorization}); when {@code
     * resumeAt} is set, sends the first {@code firstChunk} bytes, waits until then, and sends the rest.
     */
    private static int put(UploadResponseView grant, String contentType, int length, int firstChunk,
            Instant resumeAt) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(grant.url()).toURL().openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setFixedLengthStreamingMode(length);
        byte[] body = Arrays.copyOf(CLIP, length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body, 0, firstChunk);
            out.flush();
            if (resumeAt != null) {
                sleepUntil(resumeAt);
            }
            out.write(body, firstChunk, length - firstChunk);
        }
        return connection.getResponseCode();
    }

    private static boolean stored(UserView user, UploadResponseView grant) {
        try (S3Client store = objectStore()) {
            store.headObject(o -> o.bucket(UPLOAD_BUCKET).key(user.id() + "/" + grant.uploadId()));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private static void sleepUntil(Instant instant) {
        Duration wait = Duration.between(Instant.now(), instant);
        if (wait.isNegative()) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while holding the upload open", e);
        }
    }
}
