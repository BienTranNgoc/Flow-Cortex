package com.flowcortex.app.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.app.media.ImageFixtures;
import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.LocalizedText;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The content media library over HTTP against a real database and object store: grants, registration with
 * its hardened or server-side copy, the library listing and the anonymous redirect. The content check is on,
 * so a video whose bytes are not a video is refused.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.media.upload.content-check.enabled=true")
@AutoConfigureTestRestTemplate
class ContentMediaIT extends AbstractPostgresBootTest {

    private static final String PASSWORD = "correct-password-1";
    private static final String ADMIN = "/api/v1/admin/content/media";
    private static final String PUBLIC = "/api/v1/content/media";
    private static final String DOCUMENTS = "/api/v1/admin/content/documents";
    private static final String RETENTION_TAG = "flowcortex-retention";
    private static final String EXIF_MARKER = "GPS-LATITUDE-10.7769";
    private static final long IMAGE_CAP = 10L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestConfiguration
    static class Definitions {

        @Bean
        ContentDocumentDefinition itMediaDoc() {
            return new ContentDocumentDefinition("it-media-doc", new LocalizedText("Tài liệu ảnh", "Media document"),
                    List.of("richText", "media"), false);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    private TestRestTemplate noRedirects;
    private UserView owner;
    private UserView editorA;
    private UserView editorB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void arrange() {
        clearTables();
        noRedirects = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .redirects(HttpRedirects.DONT_FOLLOW)
                .rootUri(restTemplate.getRootUri()));
        owner = userService.createInitialAdmin(email("owner"), PASSWORD, "Owner");
        editorA = userService.createUser(owner.id(), email("editor-a"), PASSWORD, "Editor A", "CONTENT_EDITOR");
        editorB = userService.createUser(owner.id(), email("editor-b"), PASSWORD, "Editor B", "CONTENT_EDITOR");
        tokenA = login(editorA.email());
        tokenB = login(editorB.email());
    }

    @AfterEach
    void cleanUp() {
        clearTables();
    }

    private void clearTables() {
        jdbc.update("DELETE FROM content_document_revision WHERE slug LIKE 'it-media-%'");
        jdbc.update("DELETE FROM content_document WHERE slug LIKE 'it-media-%'");
        jdbc.update("DELETE FROM content_media");
    }

    @Test
    void aGrantForPdfMovOrAnOversizedImageIs400() {
        ResponseEntity<JsonNode> pdf = grant(tokenA, "notes.pdf", 1_000);
        ResponseEntity<JsonNode> mov = grant(tokenA, "clip.mov", 1_000);
        ResponseEntity<JsonNode> oversized = grant(tokenA, "hero.png", IMAGE_CAP + 1);
        ResponseEntity<JsonNode> atTheCap = grant(tokenA, "hero.png", IMAGE_CAP);

        for (ResponseEntity<JsonNode> refused : List.of(pdf, mov)) {
            assertProblem(refused, HttpStatus.BAD_REQUEST, "content-media-rejected");
            assertThat(refused.getBody().get("detail").asString())
                    .isEqualTo("This file type is not allowed in the content library");
        }
        assertProblem(oversized, HttpStatus.BAD_REQUEST, "content-media-rejected");
        assertThat(oversized.getBody().get("detail").asString()).isEqualTo("This file is too large for the content library");
        assertThat(atTheCap.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(atTheCap.getBody().get("maxBytes").asLong()).isEqualTo(IMAGE_CAP);
        assertThat(atTheCap.getBody().get("method").asString()).isEqualTo("PUT");
    }

    @Test
    void registeringAnImageKeepsAHardenedCopyAtContentId() {
        byte[] upload = withMetadata(ImageFixtures.png());
        UUID uploadId = granted(editorA, tokenA, "hero.png", upload, "image/png");

        ResponseEntity<JsonNode> registered = register(tokenA, uploadId, "hero.png");

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode item = registered.getBody();
        String id = item.get("id").asString();
        assertThat(item.get("kind").asString()).isEqualTo("image");
        assertThat(item.get("contentType").asString()).isEqualTo("image/png");
        assertThat(item.get("fileName").asString()).isEqualTo("hero.png");
        assertThat(item.get("uploaderEmail").asString()).isEqualTo(editorA.email());
        assertThat(item.get("url").asString()).isEqualTo(PUBLIC + "/" + id);
        assertThat(item.get("sizeBytes").asLong()).isEqualTo(ImageFixtures.png().length);
        String key = "content/" + id;
        assertThat(jdbc.queryForObject("SELECT object_key FROM content_media WHERE id = ?::uuid", String.class, id))
                .isEqualTo(key);
        byte[] kept = stored(key);
        assertThat(kept).isEqualTo(ImageFixtures.png());
        assertThat(new String(kept, StandardCharsets.ISO_8859_1)).doesNotContain(EXIF_MARKER);
        assertThat(retentionOf(key)).isEqualTo("content");
    }

    @Test
    void registeringAnMp4CopiesItServerSideAndTagsItContent() {
        byte[] video = mp4();
        UUID uploadId = granted(editorA, tokenA, "clip.mp4", video, "video/mp4");

        ResponseEntity<JsonNode> registered = register(tokenA, uploadId, "clip.mp4");

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = registered.getBody().get("id").asString();
        assertThat(registered.getBody().get("kind").asString()).isEqualTo("video");
        assertThat(registered.getBody().get("contentType").asString()).isEqualTo("video/mp4");
        assertThat(registered.getBody().get("sizeBytes").asLong()).isEqualTo(video.length);
        assertThat(stored("content/" + id)).isEqualTo(video);
        assertThat(retentionOf("content/" + id)).isEqualTo("content");
    }

    @Test
    void registeringTextNamedPngIs400AndLeavesNoItem() {
        UUID uploadId = granted(editorA, tokenA, "photo.png", "this is not an image at all".getBytes(StandardCharsets.UTF_8),
                "image/png");

        ResponseEntity<JsonNode> response = register(tokenA, uploadId, "photo.png");

        assertProblem(response, HttpStatus.BAD_REQUEST, "content-media-rejected");
        assertThat(response.getBody().get("detail").asString()).isEqualTo("The file content does not match its type");
        assertThat(mediaRows()).isZero();
    }

    @Test
    void aVideoWhoseBytesAreNotAVideoIs400AndItsCopyStaysContentPending() {
        UUID uploadId = granted(editorA, tokenA, "clip.mp4", "plain text pretending to be an mp4 clip".getBytes(
                StandardCharsets.UTF_8), "video/mp4");

        List<String> before = contentKeys();

        ResponseEntity<JsonNode> response = register(tokenA, uploadId, "clip.mp4");

        assertProblem(response, HttpStatus.BAD_REQUEST, "content-media-rejected");
        assertThat(response.getBody().get("detail").asString()).isEqualTo("The file content does not match its type");
        assertThat(mediaRows()).isZero();
        List<String> added = contentKeys().stream().filter(key -> !before.contains(key)).toList();
        assertThat(added).hasSize(1).allSatisfy(key -> assertThat(retentionOf(key)).isEqualTo("content-pending"));
    }

    @Test
    void registeringARunInputUploadIs400UploadAgain() {
        ResponseEntity<JsonNode> runInput = restTemplate.exchange("/api/v1/uploads", HttpMethod.POST,
                new HttpEntity<>(grantBody("clip.mp4", mp4().length), bearer(tokenA)), JsonNode.class);
        assertThat(runInput.getStatusCode().is2xxSuccessful()).isTrue();
        UUID runInputId = UUID.fromString(runInput.getBody().get("uploadId").asString());
        put(UPLOAD_BUCKET, editorA.id() + "/" + runInputId, mp4(), "video/mp4");
        UUID contentUpload = granted(editorA, tokenA, "hero.png", ImageFixtures.png(), "image/png");

        ResponseEntity<JsonNode> wrongPurpose = register(tokenA, runInputId, "clip.mp4");
        ResponseEntity<JsonNode> anotherOwner = register(tokenB, contentUpload, "hero.png");
        ResponseEntity<JsonNode> unknown = register(tokenA, UUID.randomUUID(), "hero.png");

        for (ResponseEntity<JsonNode> refused : List.of(wrongPurpose, anotherOwner, unknown)) {
            assertProblem(refused, HttpStatus.BAD_REQUEST, "content-media-rejected");
            assertThat(refused.getBody().get("detail").asString())
                    .isEqualTo("The uploaded file could not be used. Upload it again.");
        }
        assertThat(mediaRows()).isZero();
    }

    @Test
    void theLibraryListsNewestFirstAndFiltersByKind() {
        Instant base = Instant.parse("2026-11-01T10:00:00Z");
        UUID image1 = row("image", "image/png", "a.png", base);
        UUID video1 = row("video", "video/mp4", "b.mp4", base.plus(1, ChronoUnit.MINUTES));
        UUID image2 = row("image", "image/webp", "c.webp", base.plus(2, ChronoUnit.MINUTES));
        UUID video2 = row("video", "video/webm", "d.webm", base.plus(3, ChronoUnit.MINUTES));
        UUID image3 = row("image", "image/jpeg", "e.jpg", base.plus(4, ChronoUnit.MINUTES));

        JsonNode all = get(ADMIN, tokenA).getBody();
        JsonNode videos = get(ADMIN + "?kind=video", tokenA).getBody();
        JsonNode images = get(ADMIN + "?kind=image&size=2&page=1", tokenA).getBody();

        assertThat(ids(all)).containsExactly(image3, video2, image2, video1, image1);
        assertThat(all.get("size").asInt()).isEqualTo(24);
        assertThat(all.get("total").asLong()).isEqualTo(5);
        assertThat(ids(videos)).containsExactly(video2, video1);
        assertThat(videos.get("total").asLong()).isEqualTo(2);
        JsonNode first = videos.get("items").get(0);
        assertThat(first.get("url").asString()).isEqualTo(PUBLIC + "/" + video2);
        assertThat(first.get("fileName").asString()).isEqualTo("d.webm");
        assertThat(first.get("contentType").asString()).isEqualTo("video/webm");
        assertThat(first.get("sizeBytes").asLong()).isEqualTo(10);
        assertThat(first.get("uploaderEmail").asString()).isEqualTo(owner.email());
        assertThat(ids(images)).containsExactly(image1);
        assertThat(images.get("total").asLong()).isEqualTo(3);
    }

    @Test
    void aKindOfAudioOrSizeOf101Is400() {
        ResponseEntity<JsonNode> audio = get(ADMIN + "?kind=audio", tokenA);
        ResponseEntity<JsonNode> tooMany = get(ADMIN + "?size=101", tokenA);
        ResponseEntity<JsonNode> atTheLimit = get(ADMIN + "?size=100", tokenA);

        assertProblem(audio, HttpStatus.BAD_REQUEST, "validation-failed");
        assertThat(audio.getBody().get("errors").has("kind")).isTrue();
        assertProblem(tooMany, HttpStatus.BAD_REQUEST, "validation-failed");
        assertThat(tooMany.getBody().get("errors").has("size")).isTrue();
        assertThat(atTheLimit.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void thePublicMediaUrlRedirectsWithCacheControl() throws Exception {
        UUID uploadId = granted(editorA, tokenA, "hero.png", ImageFixtures.png(), "image/png");
        String id = register(tokenA, uploadId, "hero.png").getBody().get("id").asString();

        ResponseEntity<Void> redirect = noRedirects.exchange(PUBLIC + "/" + id, HttpMethod.GET, HttpEntity.EMPTY,
                Void.class);

        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirect.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("public, max-age=300");
        String location = redirect.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(location).startsWith(publicEndpoint() + "/" + MEDIA_BUCKET + "/content/" + id)
                .contains("X-Amz-Expires=900");
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> served = client.send(HttpRequest.newBuilder(URI.create(location)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(served.statusCode()).isEqualTo(200);
            assertThat(served.body()).isEqualTo(ImageFixtures.png());
            assertThat(served.headers().firstValue("Content-Disposition")).hasValue("attachment; filename=\"nodi-media-"
                    + id + ".png\"");
        }
    }

    @Test
    void anUnknownOrMalformedMediaIdIs404() {
        ResponseEntity<JsonNode> malformed = restTemplate.getForEntity(PUBLIC + "/not-a-uuid", JsonNode.class);
        ResponseEntity<JsonNode> unknown = restTemplate.getForEntity(PUBLIC + "/" + UUID.randomUUID(), JsonNode.class);

        for (ResponseEntity<JsonNode> missing : List.of(malformed, unknown)) {
            assertProblem(missing, HttpStatus.NOT_FOUND, "media-not-found");
            assertThat(missing.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        }
    }

    @Test
    void aDocumentSaveNamingAnUnknownMediaIdIs400() {
        ResponseEntity<JsonNode> saved = saveDocument(0, mediaDocument(UUID.randomUUID()));

        assertProblem(saved, HttpStatus.BAD_REQUEST, "document-invalid");
        assertThat(saved.getBody().get("errors").get("sections[0].data.mediaId").asString())
                .isEqualTo("This library item does not exist");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_document WHERE slug = 'it-media-doc'", Integer.class))
                .isZero();
    }

    @Test
    void aDocumentSaveNamingALibraryItemIsStored() {
        UUID uploadId = granted(editorA, tokenA, "hero.png", ImageFixtures.png(), "image/png");
        UUID id = UUID.fromString(register(tokenA, uploadId, "hero.png").getBody().get("id").asString());

        ResponseEntity<JsonNode> saved = saveDocument(0, mediaDocument(id));

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().get("sections").get(0).get("data").get("mediaId").asString()).isEqualTo(id.toString());
        assertThat(jdbc.queryForObject("SELECT sections::text FROM content_document WHERE slug = 'it-media-doc'",
                String.class)).contains(id.toString());
    }

    @Test
    void viewWithoutManageListsButCannotUpload() {
        UserView viewer = userService.createUser(owner.id(), email("viewer"), PASSWORD, "Viewer", "CUSTOMER_SUPPORT");
        String token = login(viewer.email());
        jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES ('CUSTOMER_SUPPORT', ?)",
                Permission.CONTENT_MEDIA_VIEW.key());
        try {
            ResponseEntity<JsonNode> list = get(ADMIN, token);
            ResponseEntity<JsonNode> grant = grant(token, "hero.png", 100);
            ResponseEntity<JsonNode> register = register(token, UUID.randomUUID(), "hero.png");

            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(register.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(ADMIN, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            jdbc.update("DELETE FROM role_permission WHERE role_code = 'CUSTOMER_SUPPORT' AND permission_key = ?",
                    Permission.CONTENT_MEDIA_VIEW.key());
        }
    }

    private ResponseEntity<JsonNode> grant(String token, String fileName, long size) {
        return restTemplate.exchange(ADMIN + "/uploads", HttpMethod.POST,
                new HttpEntity<>(grantBody(fileName, size), bearer(token)), JsonNode.class);
    }

    private static ObjectNode grantBody(String fileName, long size) {
        ObjectNode body = JSON.createObjectNode();
        body.put("fileName", fileName);
        body.put("size", size);
        return body;
    }

    private ResponseEntity<JsonNode> register(String token, UUID uploadId, String fileName) {
        ObjectNode body = JSON.createObjectNode();
        body.put("uploadId", uploadId.toString());
        body.put("fileName", fileName);
        return restTemplate.exchange(ADMIN, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), JsonNode.class);
    }

    /** Asks for a grant as {@code user} and stages {@code body} at the key the grant names, as a browser PUT would. */
    private UUID granted(UserView user, String token, String fileName, byte[] body, String contentType) {
        ResponseEntity<JsonNode> grant = grant(token, fileName, body.length);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID uploadId = UUID.fromString(grant.getBody().get("uploadId").asString());
        put(UPLOAD_BUCKET, "content-media/" + user.id() + "/" + uploadId, body, contentType);
        return uploadId;
    }

    private static void put(String bucket, String key, byte[] body, String contentType) {
        try (S3Client store = objectStore()) {
            store.putObject(request -> request.bucket(bucket).key(key).contentType(contentType),
                    RequestBody.fromBytes(body));
        }
    }

    private static byte[] stored(String key) {
        try (S3Client store = objectStore()) {
            return store.getObjectAsBytes(request -> request.bucket(MEDIA_BUCKET).key(key)).asByteArray();
        }
    }

    private static String retentionOf(String key) {
        try (S3Client store = objectStore()) {
            return store.getObjectTagging(request -> request.bucket(MEDIA_BUCKET).key(key)).tagSet().stream()
                    .filter(tag -> tag.key().equals(RETENTION_TAG)).map(Tag::value).findFirst().orElse(null);
        }
    }

    private static List<String> contentKeys() {
        try (S3Client store = objectStore()) {
            return store.listObjectsV2(request -> request.bucket(MEDIA_BUCKET).prefix("content/")).contents().stream()
                    .map(object -> object.key()).toList();
        }
    }

    private int mediaRows() {
        return jdbc.queryForObject("SELECT count(*) FROM content_media", Integer.class);
    }

    private UUID row(String kind, String contentType, String fileName, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO content_media (id, kind, content_type, size_bytes, bucket, object_key, file_name,"
                + " uploaded_by, created_at) VALUES (?, ?, ?, 10, ?, ?, ?, ?, ?)", id, kind, contentType, MEDIA_BUCKET,
                "content/" + id, fileName, owner.id(), Timestamp.from(createdAt));
        return id;
    }

    private static List<UUID> ids(JsonNode page) {
        return page.get("items").valueStream().map(item -> UUID.fromString(item.get("id").asString())).toList();
    }

    private ResponseEntity<JsonNode> saveDocument(int version, ArrayNode sections) {
        ObjectNode body = JSON.createObjectNode();
        body.put("version", version);
        body.set("sections", sections);
        ObjectNode seo = JSON.createObjectNode();
        seo.set("title", text("Tiêu đề", "Title"));
        seo.set("description", text("Mô tả", "Description"));
        body.set("seoMeta", seo);
        return restTemplate.exchange(DOCUMENTS + "/it-media-doc", HttpMethod.PUT, new HttpEntity<>(body, bearer(tokenA)),
                JsonNode.class);
    }

    private static ArrayNode mediaDocument(UUID mediaId) {
        ObjectNode data = JSON.createObjectNode();
        data.put("mediaId", mediaId.toString());
        data.set("alt", text("Ảnh bìa", "Cover image"));
        ObjectNode section = JSON.createObjectNode();
        section.put("type", "media");
        section.put("schemaVersion", 1);
        section.set("data", data);
        ArrayNode sections = JSON.createArrayNode();
        sections.add(section);
        return sections;
    }

    private static ObjectNode text(String vi, String en) {
        ObjectNode text = JSON.createObjectNode();
        text.put("vi", vi);
        text.put("en", en);
        return text;
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(token == null ? new HttpHeaders()
                : bearer(token)), JsonNode.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("type").asString()).isEqualTo("https://flow-cortex.dev/problems/" + code);
    }

    /** The smallest PNG with a text chunk after its header, the kind of metadata hardening drops. */
    private static byte[] withMetadata(byte[] png) {
        int afterHeader = 8 + 25;
        byte[] chunkData = ("Comment\0" + EXIF_MARKER).getBytes(StandardCharsets.ISO_8859_1);
        byte[] type = "tEXt".getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(chunkData);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Arrays.copyOfRange(png, 0, afterHeader));
        out.writeBytes(bigEndian(chunkData.length));
        out.writeBytes(type);
        out.writeBytes(chunkData);
        out.writeBytes(bigEndian(crc.getValue()));
        out.writeBytes(Arrays.copyOfRange(png, afterHeader, png.length));
        return out.toByteArray();
    }

    private static byte[] bigEndian(long value) {
        return new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    /** An ISO base media header: a 24-byte {@code ftyp} box followed by padding. */
    private static byte[] mp4() {
        byte[] header = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's',
                'o', 'm'};
        return Arrays.copyOf(header, 64);
    }

    record LoginRequest(String email, String password) {
    }

    private String login(String email) {
        JsonNode token = restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, PASSWORD),
                JsonNode.class).getBody();
        return token.get("accessToken").asString();
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
