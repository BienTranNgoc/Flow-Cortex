package com.flowcortex.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.media.application.MediaProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * The presigned upload URL, signed offline: what it binds and where it points. Whether the store
 * then enforces it is {@code UploadsEndpointIT}'s job. Also the bound on the server's own calls.
 */
class S3MediaStoreTest {

    private final S3MediaStore store = new S3MediaStore(properties("http://minio:9000", Duration.ofSeconds(5),
            Duration.ofSeconds(2)));

    private static MediaProperties properties(String endpoint, Duration apiCallTimeout,
            Duration apiCallAttemptTimeout) {
        return new MediaProperties(true, Duration.ofMinutes(30),
                Map.of(
                    "probe", Duration.ofMinutes(5),
                    "extract_audio", Duration.ofMinutes(10),
                    "transcribe", Duration.ofMinutes(2),
                    "separate_audio", Duration.ofMinutes(4),
                    "compose_dub", Duration.ofMinutes(6)),
                Duration.ofMinutes(20), Duration.ofMinutes(15),

                new MediaProperties.Upload(524_288_000L, List.of("mp4"), Duration.ofMinutes(15)),
                new MediaProperties.S3(endpoint, "http://localhost:9000", "us-east-1", true,
                        "flowcortex-uploads", "flowcortex-media", "backend-key", "backend-secret-value",
                        apiCallTimeout, apiCallAttemptTimeout));
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void aStoreThatAcceptsTheConnectionButNeverAnswersFailsTheCallWithinTheApiCallTimeout() throws IOException {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                S3MediaStore hanging = new S3MediaStore(properties("http://127.0.0.1:" + silent.getLocalPort(),
                        Duration.ofSeconds(2), Duration.ofMillis(500)))) {
            long started = System.nanoTime();

            assertThatThrownBy(() -> hanging.headBucket("flowcortex-media"))
                    .isInstanceOfAny(ApiCallTimeoutException.class, ApiCallAttemptTimeoutException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    private static Map<String, String> query(URI url) {
        return Arrays.stream(url.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    @Test
    void theUploadUrlPointsAtThePublicEndpointAndTheServerChosenKey() {
        S3MediaStore.PresignedPut put = store.presignPut("flowcortex-uploads", "owner-1/upload-1", "video/mp4", 16L,
                Duration.ofMinutes(15));

        URI url = URI.create(put.url());
        assertThat(url.getScheme() + "://" + url.getAuthority()).isEqualTo("http://localhost:9000");
        assertThat(url.getPath()).isEqualTo("/flowcortex-uploads/owner-1/upload-1");
        assertThat(query(url)).containsEntry("X-Amz-Expires", "900");
        assertThat(put.expiresAt()).isBetween(Instant.now().plus(Duration.ofMinutes(14)),
                Instant.now().plus(Duration.ofMinutes(15)));
    }

    @Test
    void theContentTypeAndTheByteCountAreSignedAndOnlyTheTypeIsHandedToTheBrowser() {
        S3MediaStore.PresignedPut put = store.presignPut("flowcortex-uploads", "owner-1/upload-1", "video/mp4", 16L,
                Duration.ofMinutes(15));

        List<String> signed = List.of(query(URI.create(put.url())).get("X-Amz-SignedHeaders").split(";"));
        assertThat(signed).contains("content-type", "host", "content-length");
        assertThat(put.headers()).containsExactly(Map.entry("Content-Type", "video/mp4"));
    }

    /** A store on a loopback port that records each request and answers with {@code status}. */
    private static HttpServer fakeStore(int status, List<String> requests) throws IOException {
        HttpServer fake = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fake.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst("Content-Type") + " "
                    + new String(body, StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("ETag", "\"e1\"");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        fake.start();
        return fake;
    }

    @Test
    void anObjectIsWrittenAtTheGivenKeyWithItsTypeAndBytes() throws IOException {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer fake = fakeStore(200, requests);
        try (S3MediaStore local = new S3MediaStore(properties("http://127.0.0.1:" + fake.getAddress().getPort(),
                Duration.ofSeconds(5), Duration.ofSeconds(2)))) {

            local.put("flowcortex-uploads", "runs/r/steps/7/voice-manifest.json", "application/json",
                    "{\"voice\":\"Kore\"}".getBytes(StandardCharsets.UTF_8));
        } finally {
            fake.stop(0);
        }

        assertThat(requests).singleElement().satisfies(request -> assertThat(request)
                .startsWith("PUT /flowcortex-uploads/runs/r/steps/7/voice-manifest.json application/json ")
                .contains("{\"voice\":\"Kore\"}"));
    }

    @Test
    void aWriteTheStoreRefusesFails() throws IOException {
        HttpServer fake = fakeStore(403, new CopyOnWriteArrayList<>());
        try (S3MediaStore local = new S3MediaStore(properties("http://127.0.0.1:" + fake.getAddress().getPort(),
                Duration.ofSeconds(5), Duration.ofSeconds(2)))) {

            assertThatThrownBy(() -> local.put("flowcortex-uploads", "runs/r/steps/7/voice.wav", "audio/wav",
                    new byte[] {1, 2, 3}))
                    .isInstanceOf(SdkException.class);
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void aPresignedPutPrintsWithoutItsUrl() {
        S3MediaStore.PresignedPut put = store.presignPut("flowcortex-uploads", "owner-1/upload-1", "video/mp4", 16L,
                Duration.ofMinutes(15));

        assertThat(put.toString()).doesNotContain("X-Amz-", "localhost:9000", "owner-1/upload-1")
                .contains("Content-Type");
    }
}
