package com.flowcortex.app.drive;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.media.ImageFixtures;
import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Tag;

/**
 * Drive items, uploads and suggestions over HTTP with spaces on and a real login: the item rules, the
 * upload-to-kept-file path against the real object store, run output pointers and the read-only suggestion list.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.spaces.enabled=true")
@AutoConfigureTestRestTemplate
class DriveItemsIT extends AbstractWorkflowHttpIT {

    private static final String ITEMS = "/api/v1/drive/items";

    @Autowired
    private JdbcTemplate jdbc;

    record ItemView(UUID id, String name, String contentType, String semanticRole, String source, String textValue,
            String url, Instant urlExpiresAt, String fileName, String mimeType, Long sizeBytes, UUID runId,
            Integer stepOrder, Instant createdAt, Instant updatedAt) {
    }

    record PageOfItems(List<ItemView> items, int page, int size, long total) {
    }

    record ProblemView(int status, String detail, Map<String, String> errors) {
    }

    record GrantView(UUID uploadId, String url, Map<String, String> headers) {
    }

    record RunOutputView(UUID runId, int stepOrder, String name) {
    }

    record SuggestionView(String id, String kind, String previewText, String textValue, boolean tooLong,
            String roleHint, UUID runId, RunOutputView runOutput, String url) {
    }

    @Test
    void textItemRoundTripCreateListPatchDelete() {
        String token = loginAs(newUser("drive-text"));

        ResponseEntity<ItemView> created = send(HttpMethod.POST, ITEMS, token,
                Map.of("name", "  Brand voice  ", "contentType", "text", "semanticRole", "brand_voice_text",
                        "textValue", "Warm and direct"), ItemView.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ItemView item = created.getBody();
        assertThat(item.name()).isEqualTo("Brand voice");
        assertThat(item.textValue()).isEqualTo("Warm and direct");
        assertThat(item.url()).isNull();
        send(HttpMethod.POST, ITEMS, token, Map.of("name", "Logo note", "contentType", "text",
                "semanticRole", "company_bio", "textValue", "We make tools"), ItemView.class);

        PageOfItems page = send(HttpMethod.GET, ITEMS + "?role=company_bio", token, null, PageOfItems.class).getBody();
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(ItemView::name).containsExactly("Logo note");
        PageOfItems all = send(HttpMethod.GET, ITEMS, token, null, PageOfItems.class).getBody();
        assertThat(all.items()).extracting(ItemView::name).containsExactly("Logo note", "Brand voice");

        ResponseEntity<ItemView> renamed = send(HttpMethod.PATCH, ITEMS + "/" + item.id(), token,
                Map.of("name", "  New voice  "), ItemView.class);
        assertThat(renamed.getBody().name()).isEqualTo("New voice");
        assertThat(renamed.getBody().textValue()).isEqualTo("Warm and direct");
        assertThat(renamed.getBody().semanticRole()).isEqualTo("brand_voice_text");

        ResponseEntity<ProblemView> crossType = send(HttpMethod.PATCH, ITEMS + "/" + item.id(), token,
                Map.of("semanticRole", "driver_video"), ProblemView.class);
        assertThat(crossType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(crossType.getBody().errors()).containsKey("semanticRole");
        assertThat(send(HttpMethod.GET, ITEMS + "/" + item.id(), token, null, ItemView.class).getBody().semanticRole())
                .isEqualTo("brand_voice_text");

        assertThat(send(HttpMethod.DELETE, ITEMS + "/" + item.id(), token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(send(HttpMethod.GET, ITEMS + "/" + item.id(), token, null, ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aTextOver32768CharactersIsRefusedWithTheVietnameseSentenceForAViewer() throws IOException {
        String token = loginAs(newUser("drive-vi"));
        Map<String, String> body = Map.of("name", "Bio", "contentType", "text", "semanticRole", "company_bio",
                "textValue", "x".repeat(32_769));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");

        ResponseEntity<ProblemView> refused = restTemplate.exchange(ITEMS, HttpMethod.POST,
                new HttpEntity<>(body, headers), ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().errors().get("textValue")).isEqualTo(vietnameseBundle().getProperty("drive.text.invalid"));
        assertThat(send(HttpMethod.POST, ITEMS, token, Map.of("name", "Bio", "contentType", "text",
                "semanticRole", "company_bio", "textValue", "x".repeat(32_768)), ItemView.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anUploadedImageIsKeptUnderTheSpaceAndItemAndDeletingItReleasesTheCopy() {
        UserView user = newUser("drive-upload");
        String token = loginAs(user);
        byte[] png = ImageFixtures.png();
        GrantView grant = send(HttpMethod.POST, "/api/v1/drive/uploads", token,
                Map.of("fileName", "logo.png", "size", png.length), GrantView.class).getBody();
        put(grant, png);

        ResponseEntity<ItemView> created = send(HttpMethod.POST, ITEMS, token, Map.of("name", "Logo",
                "contentType", "image", "semanticRole", "logo", "uploadId", grant.uploadId()), ItemView.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ItemView item = created.getBody();
        assertThat(item.source()).isEqualTo("upload");
        assertThat(item.url()).isNotBlank();
        assertThat(item.urlExpiresAt()).isNotNull();
        UUID space = jdbc.queryForObject("SELECT space_id FROM drive_item WHERE id = ?", UUID.class, item.id());
        String key = "drive/" + space + "/" + item.id();
        assertThat(jdbc.queryForObject("SELECT object_key FROM drive_item WHERE id = ?", String.class, item.id()))
                .isEqualTo(key);
        assertThat(retention(key)).isEqualTo("drive-file");

        assertThat(send(HttpMethod.DELETE, ITEMS + "/" + item.id(), token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(retention(key)).isEqualTo("drive-pending");
    }

    @Test
    void aRunInputUploadIdIsRefusedAsADriveFile() {
        String token = loginAs(newUser("drive-runinput"));
        byte[] png = ImageFixtures.png();
        GrantView grant = send(HttpMethod.POST, "/api/v1/uploads", token,
                Map.of("fileName", "logo.png", "size", png.length), GrantView.class).getBody();
        put(grant, png);

        ResponseEntity<ProblemView> refused = send(HttpMethod.POST, ITEMS, token, Map.of("name", "Logo",
                "contentType", "image", "semanticRole", "logo", "uploadId", grant.uploadId()), ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().errors()).containsKey("uploadId");
        assertThat(send(HttpMethod.GET, ITEMS, token, null, PageOfItems.class).getBody().total()).isZero();
    }

    @Test
    void aPngCreatedAsVideoIsRefusedOnContentType() {
        String token = loginAs(newUser("drive-kind"));
        byte[] png = ImageFixtures.png();
        GrantView grant = send(HttpMethod.POST, "/api/v1/drive/uploads", token,
                Map.of("fileName", "logo.png", "size", png.length), GrantView.class).getBody();
        put(grant, png);

        ResponseEntity<ProblemView> refused = send(HttpMethod.POST, ITEMS, token, Map.of("name", "Clip",
                "contentType", "video", "semanticRole", "driver_video", "uploadId", grant.uploadId()),
                ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().errors()).containsKey("contentType");
        assertThat(send(HttpMethod.GET, ITEMS, token, null, PageOfItems.class).getBody().total()).isZero();
    }

    @Test
    void anImageOver25MegapixelsIsRefusedOnTheUploadFieldAndNoItemExists() {
        String token = loginAs(newUser("drive-pixels"));
        byte[] huge = ImageFixtures.png(5001, 5000);
        GrantView grant = send(HttpMethod.POST, "/api/v1/drive/uploads", token,
                Map.of("fileName", "huge.png", "size", huge.length), GrantView.class).getBody();
        put(grant, huge);

        ResponseEntity<ProblemView> refused = send(HttpMethod.POST, ITEMS, token, Map.of("name", "Photo",
                "contentType", "image", "semanticRole", "product_photo", "uploadId", grant.uploadId()),
                ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().errors().get("uploadId")).contains("25 megapixels");
        assertThat(send(HttpMethod.GET, ITEMS, token, null, PageOfItems.class).getBody().total()).isZero();
    }

    @Test
    void aMediaItemWithABlankNameIsRefusedOnName() {
        String token = loginAs(newUser("drive-name"));

        ResponseEntity<ProblemView> refused = send(HttpMethod.POST, ITEMS, token, Map.of("name", "   ",
                "contentType", "image", "semanticRole", "logo", "uploadId", UUID.randomUUID()), ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().errors()).containsKey("name");
    }

    @Test
    void anotherMembersRunOutputIs404AndARunningRunIs400() {
        UserView owner = newUser("drive-run-owner");
        UserView other = newUser("drive-run-other");
        UUID runId = completedEchoRun(owner, "hello drive");
        recordImageOutput(runId);
        Map<String, Object> ref = Map.of("runId", runId, "stepOrder", 1, "name", "promo.png");
        Map<String, Object> body = Map.of("name", "Promo", "contentType", "image", "semanticRole", "product_photo",
                "runOutput", ref);

        ResponseEntity<ProblemView> foreign = send(HttpMethod.POST, ITEMS, loginAs(other), body, ProblemView.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        jdbc.update("UPDATE workflow_run SET status = 'running' WHERE id = ?", runId);
        ResponseEntity<ProblemView> running = send(HttpMethod.POST, ITEMS, loginAs(owner), body, ProblemView.class);
        assertThat(running.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(running.getBody().errors()).containsKey("runOutput");
        assertThat(send(HttpMethod.GET, ITEMS, loginAs(owner), null, PageOfItems.class).getBody().total()).isZero();
    }

    @Test
    void aRunOutputItemPointsAtTheOutputAndDeletingItLeavesTheRunOutput() {
        UserView owner = newUser("drive-run-pointer");
        String token = loginAs(owner);
        UUID runId = completedEchoRun(owner, "hello pointer");
        recordImageOutput(runId);

        ResponseEntity<ItemView> created = send(HttpMethod.POST, ITEMS, token, Map.of("name", "Promo",
                "contentType", "image", "semanticRole", "product_photo",
                "runOutput", Map.of("runId", runId, "stepOrder", 1, "name", "promo.png")), ItemView.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ItemView item = created.getBody();
        assertThat(item.source()).isEqualTo("run_output");
        assertThat(item.runId()).isEqualTo(runId);
        assertThat(item.url()).contains("runs/" + runId + "/steps/1/promo.png");
        ResponseEntity<ProblemView> wrongKind = send(HttpMethod.POST, ITEMS, token, Map.of("name", "Promo",
                "contentType", "video", "semanticRole", "driver_video",
                "runOutput", Map.of("runId", runId, "stepOrder", 1, "name", "promo.png")), ProblemView.class);
        assertThat(wrongKind.getBody().errors()).containsKey("runOutput");

        assertThat(send(HttpMethod.DELETE, ITEMS + "/" + item.id(), token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT outputs::text FROM run_step WHERE run_id = ?", String.class, runId))
                .contains("promo.png");
    }

    @Test
    void anotherMembersItemAndAnUnknownIdAre404Alike() {
        String owner = loginAs(newUser("drive-iso-owner"));
        String other = loginAs(newUser("drive-iso-other"));
        ItemView item = send(HttpMethod.POST, ITEMS, owner, Map.of("name", "Mine", "contentType", "text",
                "semanticRole", "company_bio", "textValue", "private"), ItemView.class).getBody();

        ResponseEntity<ProblemView> foreign = send(HttpMethod.GET, ITEMS + "/" + item.id(), other, null,
                ProblemView.class);
        ResponseEntity<ProblemView> unknown = send(HttpMethod.GET, ITEMS + "/" + UUID.randomUUID(), other, null,
                ProblemView.class);

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody().detail()).isEqualTo("Drive item not found").isEqualTo(unknown.getBody().detail());
        assertThat(send(HttpMethod.DELETE, ITEMS + "/" + item.id(), other, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PATCH, ITEMS + "/" + item.id(), other, Map.of("name", "x"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.GET, ITEMS + "/" + item.id(), owner, null, ItemView.class).getBody().name())
                .isEqualTo("Mine");
    }

    @Test
    void pageSize101Is400() {
        ResponseEntity<ProblemView> refused = send(HttpMethod.GET, ITEMS + "?size=101",
                loginAs(newUser("drive-size")), null, ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().errors()).containsKey("size");
    }

    @Test
    void suggestionsStoreNothingAndLimit51Is400() {
        UserView owner = newUser("drive-suggest");
        String token = loginAs(owner);
        UUID runId = completedEchoRun(owner, "a candidate text that is certainly longer than forty characters");
        recordImageOutput(runId);

        ResponseEntity<SuggestionView[]> listed = send(HttpMethod.GET, "/api/v1/drive/suggestions", token, null,
                SuggestionView[].class);

        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        SuggestionView[] suggestions = listed.getBody();
        assertThat(suggestions).extracting(SuggestionView::kind).contains("text", "image");
        SuggestionView image = List.of(suggestions).stream().filter(s -> "image".equals(s.kind())).findFirst()
                .orElseThrow();
        assertThat(image.runOutput()).isEqualTo(new RunOutputView(runId, 1, "promo.png"));
        assertThat(image.url()).isNotBlank();
        assertThat(image.roleHint()).isEqualTo("product_photo");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drive_item WHERE owner_id = ?", Long.class, owner.id()))
                .isZero();

        ResponseEntity<ProblemView> refused = send(HttpMethod.GET, "/api/v1/drive/suggestions?limit=51", token, null,
                ProblemView.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().errors()).containsKey("limit");
    }

    @Test
    void keepingA300CharacterTextCandidateStoresAllOfIt() {
        UserView owner = newUser("drive-long");
        String token = loginAs(owner);
        String text = "w".repeat(300);
        completedEchoRun(owner, text);

        SuggestionView candidate = List.of(send(HttpMethod.GET, "/api/v1/drive/suggestions", token, null,
                SuggestionView[].class).getBody()).stream()
                .filter(s -> "text".equals(s.kind()) && s.textValue() != null).findFirst().orElseThrow();
        assertThat(candidate.previewText()).hasSize(160);
        assertThat(candidate.textValue()).hasSize(300);
        assertThat(candidate.tooLong()).isFalse();

        ItemView kept = send(HttpMethod.POST, ITEMS, token, Map.of("name", "Kept", "contentType", "text",
                "semanticRole", "audience_note", "textValue", candidate.textValue()), ItemView.class).getBody();

        assertThat(kept.textValue()).hasSize(300);
    }

    private <T> ResponseEntity<T> send(HttpMethod method, String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    /** PUTs {@code body} to the object the grant names, as the member's browser would have. */
    private void put(GrantView grant, byte[] body) {
        String path = URI.create(grant.url()).getPath().substring(1);
        int slash = path.indexOf('/');
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(path.substring(0, slash)).key(path.substring(slash + 1))
                    .contentType(grant.headers().get("Content-Type")), RequestBody.fromBytes(body));
        }
    }

    private String retention(String key) {
        try (S3Client store = objectStore()) {
            return store.getObjectTagging(o -> o.bucket(MEDIA_BUCKET).key(key)).tagSet().stream()
                    .filter(tag -> "flowcortex-retention".equals(tag.key())).map(Tag::value).findFirst().orElse(null);
        }
    }

    private UUID completedEchoRun(UserView owner, String input) {
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        RunView run = workflowRunService.startRun(owner.id(), workflow.id(), input);
        awaitTerminal(owner.id(), run.id());
        return run.id();
    }

    /** Records an image the way a worker's result would: below the step's own output prefix of the media bucket. */
    private void recordImageOutput(UUID runId) {
        String outputs = "[{\"name\":\"promo.png\",\"bucket\":\"" + MEDIA_BUCKET + "\",\"objectKey\":\"runs/" + runId
                + "/steps/1/promo.png\",\"mime\":\"image/png\",\"bytes\":3}]";
        jdbc.update("UPDATE run_step SET outputs = ?::jsonb WHERE run_id = ? AND step_order = 1", outputs, runId);
    }

    private static Properties vietnameseBundle() throws IOException {
        Properties bundle = new Properties();
        try (InputStream in = DriveItemsIT.class.getResourceAsStream("/i18n/messages_vi.properties")) {
            bundle.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return bundle;
    }
}
