package com.flowcortex.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.media.api.UploadRejectedException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.s3.model.S3Exception;

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
                        "align_audio", Duration.ofMinutes(10),
                        "render_video", Duration.ofMinutes(10)),
                Duration.ofMinutes(20),
                Duration.ofMinutes(2), Duration.ofMinutes(5),
                Duration.ofMinutes(15),

                new MediaProperties.Upload(524_288_000L, 104_857_600L, 10_485_760L, List.of("mp4"), List.of("jpg"),
                        Duration.ofMinutes(15), new MediaProperties.Upload.ContentCheck(true)),
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

    /** A one-route stand-in for the store: answers every request with {@code status} and {@code body}. */
    private static HttpServer fakeStore(int status, String body, Map<String, String> requestHeaders)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestHeaders().forEach((name, values) ->
                    requestHeaders.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static S3MediaStore storeAt(HttpServer server) {
        return new S3MediaStore(properties("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(5), Duration.ofSeconds(2)));
    }

    @Test
    void aRunInputCopyIsTaggedForRetentionInsteadOfInheritingTheUploadsTags() throws IOException {
        Map<String, String> headers = new ConcurrentHashMap<>();
        HttpServer server = fakeStore(200, "<CopyObjectResult><ETag>\"etag\"</ETag>"
                + "<LastModified>2026-10-14T10:00:00.000Z</LastModified></CopyObjectResult>", headers);
        try (S3MediaStore local = storeAt(server)) {
            local.copy("flowcortex-uploads", "owner/upload", "flowcortex-media", "runs/r/inputs/image_1");
        } finally {
            server.stop(0);
        }

        assertThat(headers).containsEntry("x-amz-tagging", "flowcortex-retention=run-input")
                .containsEntry("x-amz-tagging-directive", "REPLACE")
                .containsEntry("x-amz-copy-source", "flowcortex-uploads/owner/upload");
    }

    @Test
    void onlyASourceThatVanishedBeforeTheCopyIsRefusedAsAnUploadToRepeat() throws IOException {
        HttpServer missing = fakeStore(404, "<Error><Code>NoSuchKey</Code>"
                + "<Message>The specified key does not exist.</Message></Error>", new ConcurrentHashMap<>());
        try (S3MediaStore local = storeAt(missing)) {
            assertThatThrownBy(() -> local.copy("flowcortex-uploads", "owner/upload", "flowcortex-media",
                    "runs/r/inputs/image_1"))
                    .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                        assertThat(e.field()).isEqualTo("uploadIds");
                        assertThat(e.getMessage()).isEqualTo("Upload the file again");
                    });
        } finally {
            missing.stop(0);
        }

        HttpServer denied = fakeStore(403, "<Error><Code>AccessDenied</Code><Message>Access Denied.</Message></Error>",
                new ConcurrentHashMap<>());
        try (S3MediaStore local = storeAt(denied)) {
            assertThatThrownBy(() -> local.copy("flowcortex-uploads", "owner/upload", "flowcortex-media",
                    "runs/r/inputs/image_1"))
                    .isInstanceOf(S3Exception.class)
                    .isNotInstanceOf(UploadRejectedException.class);
        } finally {
            denied.stop(0);
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

    @Test
    void aPresignedPutPrintsWithoutItsUrl() {
        S3MediaStore.PresignedPut put = store.presignPut("flowcortex-uploads", "owner-1/upload-1", "video/mp4", 16L,
                Duration.ofMinutes(15));

        assertThat(put.toString()).doesNotContain("X-Amz-", "localhost:9000", "owner-1/upload-1")
                .contains("Content-Type");
    }
}
