package com.flowcortex.app.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.media.ImageFixtures;
import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.user.api.UserView;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Tag;

/** {@code /api/v1/profile/images} against the real app, Postgres and an object store. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ProfileImagesIT extends AbstractWorkflowHttpIT {

    private static final String BASE = "/api/v1/profile/images";
    private static final String RETENTION_TAG = "flowcortex-retention";

    @Autowired
    private JdbcTemplate jdbc;

    record UploadRequestView(String fileName, Long size) {
    }

    record UploadResponseView(UUID uploadId, String url, String method, Map<String, String> headers,
            Instant expiresAt, long maxBytes) {
    }

    record UploadedImageRequestView(UUID uploadId) {
    }

    record ImageView(UUID id, String url, Instant expiresAt, int width, int height) {
    }

    record CurrentView(ImageView avatar, ImageView cover) {
    }

    private UUID upload(String token, byte[] png) throws IOException {
        ResponseEntity<UploadResponseView> grant = post(BASE + "/uploads", token,
                new UploadRequestView("photo.png", (long) png.length), UploadResponseView.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UploadResponseView body = grant.getBody();
        HttpURLConnection connection = (HttpURLConnection) URI.create(body.url()).toURL().openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        body.headers().forEach(connection::setRequestProperty);
        connection.setFixedLengthStreamingMode(png.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(png);
        }
        assertThat(connection.getResponseCode()).isEqualTo(200);
        return body.uploadId();
    }

    private ResponseEntity<String> put(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<ImageView> addOriginal(String token, String slot, UUID uploadId) {
        return post(BASE + "/" + slot + "/originals", token, new UploadedImageRequestView(uploadId), ImageView.class);
    }

    private String retention(String key) {
        try (S3Client store = objectStore()) {
            return store.getObjectTagging(t -> t.bucket(MEDIA_BUCKET).key(key)).tagSet().stream()
                    .filter(tag -> RETENTION_TAG.equals(tag.key())).map(Tag::value).findFirst().orElse(null);
        }
    }

    @Test
    void newMemberHasNoImages() {
        String token = loginAs(newUser("images-empty"));

        ResponseEntity<String> response = get(BASE, token, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"avatar\":null").contains("\"cover\":null");
    }

    @Test
    void originalsListNewestFirst() throws IOException {
        String token = loginAs(newUser("images-order"));
        ResponseEntity<ImageView> first = addOriginal(token, "avatar", upload(token, ImageFixtures.png(40, 30)));
        ResponseEntity<ImageView> second = addOriginal(token, "avatar", upload(token, ImageFixtures.png(50, 40)));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<ImageView[]> list = get(BASE + "/avatar/originals", token, ImageView[].class);
        assertThat(list.getBody()).extracting(ImageView::id)
                .containsExactly(second.getBody().id(), first.getBody().id());
        assertThat(list.getBody()[0].width()).isEqualTo(50);
        assertThat(list.getBody()[0].url()).contains("X-Amz-");
        assertThat(retention(keyOf(second.getBody().id()))).isEqualTo("profile-image");
        assertThat(get(BASE + "/cover/originals", token, ImageView[].class).getBody()).isEmpty();
    }

    @Test
    void thirtyFirstOriginalDropsTheOldestAndReleasesIt() throws IOException {
        UserView user = newUser("images-cap");
        String token = loginAs(user);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            ResponseEntity<ImageView> added = addOriginal(token, "cover", upload(token, ImageFixtures.png(300, 100)));
            assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ids.add(added.getBody().id());
        }

        ImageView[] list = get(BASE + "/cover/originals", token, ImageView[].class).getBody();

        assertThat(list).hasSize(30);
        assertThat(list[0].id()).isEqualTo(ids.get(30));
        assertThat(list).extracting(ImageView::id).doesNotContain(ids.get(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profile_image WHERE id = ?", Long.class, ids.get(0)))
                .isZero();
        assertThat(retention("profile/" + user.id() + "/cover/" + ids.get(0))).isEqualTo("profile-released");
        assertThat(retention("profile/" + user.id() + "/cover/" + ids.get(1))).isEqualTo("profile-image");
    }

    @Test
    void anotherOwnersUploadIdIs400() throws IOException {
        String owner = loginAs(newUser("images-owner"));
        String other = loginAs(newUser("images-other"));
        UUID ownersUpload = upload(owner, ImageFixtures.png(512, 512));

        ResponseEntity<String> original = post(BASE + "/avatar/originals", other,
                new UploadedImageRequestView(ownersUpload), String.class);
        ResponseEntity<String> current = put(BASE + "/avatar", other, new UploadedImageRequestView(ownersUpload));

        assertThat(original.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(original.getBody()).contains("\"uploadId\"");
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(BASE, other, String.class).getBody()).contains("\"avatar\":null");
    }

    @Test
    void misshapedCropIs422AndCurrentUnchanged() throws IOException {
        String token = loginAs(newUser("images-shape"));
        ResponseEntity<String> good = put(BASE + "/avatar", token,
                new UploadedImageRequestView(upload(token, ImageFixtures.png(512, 512))));
        assertThat(good.getStatusCode()).isEqualTo(HttpStatus.OK);
        ImageView before = get(BASE, token, CurrentView.class).getBody().avatar();

        ResponseEntity<String> refused = put(BASE + "/avatar", token,
                new UploadedImageRequestView(upload(token, ImageFixtures.png(512, 514))));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody()).contains("\"uploadId\":\"The cropped image does not fit.\"");
        assertThat(get(BASE, token, CurrentView.class).getBody().avatar().id()).isEqualTo(before.id());
        ResponseEntity<String> cover = put(BASE + "/cover", token,
                new UploadedImageRequestView(upload(token, ImageFixtures.png(1500, 600))));
        assertThat(cover.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(put(BASE + "/cover", token,
                new UploadedImageRequestView(upload(token, ImageFixtures.png(1500, 500)))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void concurrentCurrentSavesLeaveOneCurrent() throws Exception {
        UserView user = newUser("images-race");
        String token = loginAs(user);
        UUID first = upload(token, ImageFixtures.png(512, 512));
        UUID second = upload(token, ImageFixtures.png(256, 256));
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<ResponseEntity<String>> a = CompletableFuture.supplyAsync(() -> {
            await(start);
            return put(BASE + "/avatar", token, new UploadedImageRequestView(first));
        });
        CompletableFuture<ResponseEntity<String>> b = CompletableFuture.supplyAsync(() -> {
            await(start);
            return put(BASE + "/avatar", token, new UploadedImageRequestView(second));
        });
        start.countDown();

        assertThat(a.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profile_image WHERE user_id = ? AND slot = 'avatar' "
                + "AND role = 'current'", Long.class, user.id())).isEqualTo(1L);
        String keptKey = jdbc.queryForObject("SELECT object_key FROM profile_image WHERE user_id = ?", String.class,
                user.id());
        assertThat(retention(keptKey)).isEqualTo("profile-image");
    }

    private String keyOf(UUID imageId) {
        return jdbc.queryForObject("SELECT object_key FROM profile_image WHERE id = ?", String.class, imageId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted before the race started", e);
        }
    }
}
