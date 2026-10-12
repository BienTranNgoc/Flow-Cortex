package com.flowcortex.app.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.media.application.MediaProperties;
import com.flowcortex.media.infrastructure.S3MediaStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * A download URL used after its expiry is refused by the object store itself, so a leaked URL
 * stops working on time. The TTL is shortened to one second on the store the backend uses.
 */
class PresignedGetExpiryIT extends AbstractPostgresBootTest {

    private static final String KEY = "runs/00000000-0000-0000-0000-000000000001/steps/1/video-details.json";

    @Test
    void anExpiredDownloadUrlIsRefusedWith403() throws Exception {
        try (S3Client root = objectStore()) {
            root.putObject(o -> o.bucket(MEDIA_BUCKET).key(KEY).contentType("application/json"),
                    RequestBody.fromString("{}"));
        }
        try (S3MediaStore store = new S3MediaStore(properties()); HttpClient client = HttpClient.newHttpClient()) {
            S3MediaStore.PresignedGet get = store.presignGet(MEDIA_BUCKET, KEY,
                    "attachment; filename=\"video-details.json\"", Duration.ofSeconds(5));
            HttpRequest request = HttpRequest.newBuilder(URI.create(get.url())).GET().build();

            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

            Thread.sleep(Math.max(0, Duration.between(Instant.now(), get.expiresAt()).toMillis()) + 1_500);
            HttpResponse<String> expired = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(expired.statusCode()).isEqualTo(403);
            assertThat(expired.body()).doesNotContain("{}").contains("AccessDenied");
        }
    }

    private static MediaProperties properties() {
        return new MediaProperties(true, Duration.ofMinutes(30), Map.of(
            "probe", Duration.ofMinutes(5),
            "extract_audio", Duration.ofMinutes(10),
            "align_audio", Duration.ofMinutes(10),
            "render_video", Duration.ofMinutes(10)),
                Duration.ofMinutes(20), Duration.ofSeconds(1),
                new MediaProperties.Upload(524_288_000L, List.of("mp4"),
                        List.of("jpg", "jpeg", "png", "webp"), Duration.ofMinutes(15)),
                new MediaProperties.S3(internalEndpoint(), publicEndpoint(), "us-east-1", true, UPLOAD_BUCKET,
                        MEDIA_BUCKET, SILO.getUserName(), SILO.getPassword(), Duration.ofSeconds(5),
                        Duration.ofSeconds(2)));
    }
}
