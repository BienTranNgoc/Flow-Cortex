package com.flowcortex.app.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.flowcortex.media.api.MediaStoreUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code download-url} and {@code view-url} against the real object store: the links are fetched from the
 * test JVM as the member's browser would, and a removed object, a restored one and a store that does not
 * answer each leave the asset's mark as the routes promise.
 */
@ExtendWith(OutputCaptureExtension.class)
class AssetLinksEndpointsIT extends AbstractAssetHttpIT {

    private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*=UTF-8''([^;]+)");
    private static final byte[] CLIP = "0123456789 and then some more bytes".getBytes(StandardCharsets.UTF_8);

    record LinkView(String url, Instant expiresAt) {
    }

    record ItemView(UUID id, boolean missing) {
    }

    record PageView(List<ItemView> items) {
    }

    @Test
    void downloadUrlSavesUnderTheTitleWithItsExtension() throws Exception {
        Member member = member("link-download");
        String title = "Áo thun cotton trắng · Product video";
        UUID video = member.storedAsset("video", title, "video/mp4", CLIP, Instant.now().minus(Duration.ofHours(1)));
        UUID photo = member.storedAsset("image", "ao-thun.jpg", "image/jpeg", CLIP, Instant.now());
        Instant before = Instant.now();

        ResponseEntity<LinkView> response = member.get("/" + video + "/download-url", LinkView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().expiresAt())
                .isBetween(before.plus(Duration.ofMinutes(14)), Instant.now().plus(Duration.ofMinutes(16)));
        HttpResponse<byte[]> download = fetch(response.getBody().url(), null);
        assertThat(download.statusCode()).isEqualTo(200);
        String disposition = download.headers().firstValue("content-disposition").orElseThrow();
        assertThat(disposition).startsWith("attachment;");
        assertThat(savedName(disposition)).isEqualTo(title + ".mp4");

        LinkView photoLink = member.get("/" + photo + "/download-url", LinkView.class).getBody();
        assertThat(savedName(fetch(photoLink.url(), null).headers().firstValue("content-disposition").orElseThrow()))
                .isEqualTo("ao-thun.jpg");
    }

    @Test
    void viewUrlIsInlineAndServesARange() throws Exception {
        Member member = member("link-view");
        UUID video = member.storedAsset("video", "Clip", "video/mp4", CLIP, Instant.now());

        ResponseEntity<LinkView> response = member.get("/" + video + "/view-url", LinkView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpResponse<byte[]> part = fetch(response.getBody().url(), "bytes=0-9");
        assertThat(part.statusCode()).isEqualTo(206);
        assertThat(new String(part.body(), StandardCharsets.UTF_8)).isEqualTo("0123456789");
        assertThat(part.headers().firstValue("content-disposition").orElseThrow()).startsWith("inline;");
        assertThat(part.headers().firstValue("content-type")).hasValue("video/mp4");
    }

    @Test
    void goneObjectIs410AndListShowsMissing(CapturedOutput output) {
        Member member = member("link-gone");
        String title = "A title that must stay out of the logs";
        UUID asset = member.upload("video", title, Instant.now().minus(Duration.ofHours(1)));

        ResponseEntity<ProblemView> first = member.get("/" + asset + "/download-url", ProblemView.class);
        Instant firstMark = member.missingAt(asset);
        ResponseEntity<ProblemView> second = member.get("/" + asset + "/view-url", ProblemView.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(firstMark).isNotNull();
        assertThat(member.missingAt(asset)).isEqualTo(firstMark);
        assertThat(missingOf(member, asset)).isTrue();
        assertThat(output.getAll()).contains("outcome=file_gone").contains("asset=" + asset)
                .doesNotContain(title).doesNotContain("X-Amz-");
    }

    @Test
    void restoredObjectClearsTheMark() {
        Member member = member("link-restored");
        UUID asset = member.upload("video", "Clip", Instant.now().minus(Duration.ofHours(1)));
        assertThat(member.get("/" + asset + "/download-url", ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
        assertThat(missingOf(member, asset)).isTrue();

        member.putObject(member.objectKeyOf(asset), "video/mp4", CLIP);
        ResponseEntity<LinkView> response = member.get("/" + asset + "/view-url", LinkView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(member.missingAt(asset)).isNull();
        assertThat(missingOf(member, asset)).isFalse();
    }

    @Test
    void storeDownIs503AndMarksNothing() {
        Member member = member("link-store-down");
        UUID unmarked = member.upload("video", "Never probed", Instant.now().minus(Duration.ofHours(2)));
        UUID marked = member.upload("video", "Already gone", Instant.now().minus(Duration.ofHours(1)));
        member.markMissing(marked);
        Instant mark = member.missingAt(marked);
        doThrow(new MediaStoreUnavailableException("store down", null)).when(assetFiles).probe(any(), any());

        ResponseEntity<ProblemView> download = member.get("/" + unmarked + "/download-url", ProblemView.class);
        ResponseEntity<ProblemView> view = member.get("/" + marked + "/view-url", ProblemView.class);

        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(member.missingAt(unmarked)).isNull();
        assertThat(member.missingAt(marked)).isEqualTo(mark);
    }

    @Test
    void assetOutsideTheMediaBucketIs503AndMarksNothing(CapturedOutput output) {
        Member member = member("link-foreign-bucket");
        UUID asset = member.storedAsset("video", "Moved bucket", "video/mp4", CLIP, Instant.now());
        jdbc.update("UPDATE space_asset SET bucket = 'flowcortex-renamed' WHERE id = ?", asset);

        ResponseEntity<ProblemView> download = member.get("/" + asset + "/download-url", ProblemView.class);
        ResponseEntity<ProblemView> view = member.get("/" + asset + "/view-url", ProblemView.class);

        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(member.missingAt(asset)).isNull();
        assertThat(output.getAll()).contains("outcome=outside_media_bucket").contains("asset=" + asset);
    }

    @Test
    void anotherMembersAssetLinkIs404() {
        Member owner = member("link-owner");
        Member intruder = member("link-intruder");
        UUID asset = owner.storedAsset("video", "Private", "video/mp4", CLIP, Instant.now());

        for (String path : List.of("/" + asset + "/download-url", "/" + asset + "/view-url",
                "/" + UUID.randomUUID() + "/download-url", "/not-a-uuid/view-url")) {
            ResponseEntity<String> response = intruder.get(path, String.class);

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).as(path).doesNotContain("X-Amz-");
        }
        assertThat(owner.get("/" + asset + "/download-url", LinkView.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static boolean missingOf(Member member, UUID id) {
        ResponseEntity<PageView> page = member.get("?pageSize=100", PageView.class);
        return page.getBody().items().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow().missing();
    }

    /** The UTF-8 name a browser would save under, read from the RFC 6266 {@code filename*} parameter. */
    private static String savedName(String disposition) {
        Matcher matcher = FILENAME_STAR.matcher(disposition);
        assertThat(matcher.find()).as(disposition).isTrue();
        return URLDecoder.decode(matcher.group(1).replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static HttpResponse<byte[]> fetch(String url, String range) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (range != null) {
            request.header("Range", range);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }
}
