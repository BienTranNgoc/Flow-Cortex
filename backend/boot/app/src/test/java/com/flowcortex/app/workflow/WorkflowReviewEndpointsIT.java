package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Bookmark and review routes over HTTP: routing, who may call them, request binding and error mapping. The rules
 * behind them are covered by the service tests and the repository IT.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class WorkflowReviewEndpointsIT extends AbstractWorkflowHttpIT {

    private static final String WORKFLOWS = "/api/v1/workflows/";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aMemberSavesListsAndUnsavesAWorkflow() {
        UserView ana = newUser("bm-ana");
        UserView ben = member(ana, "bm-ben");
        UUID first = publicWorkflow(ana);
        UUID second = publicWorkflow(ana);
        String token = loginAs(ben);

        assertThat(call(HttpMethod.GET, WORKFLOWS + first + "/bookmark", token, null).getBody())
                .isEqualTo(Map.of("bookmarked", false));
        assertThat(call(HttpMethod.PUT, WORKFLOWS + first + "/bookmark", token, Map.of("bookmarked", true))
                .getBody()).isEqualTo(Map.of("bookmarked", true));
        assertThat(call(HttpMethod.PUT, WORKFLOWS + second + "/bookmark", token, Map.of("bookmarked", true))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> page = call(HttpMethod.GET, WORKFLOWS + "bookmarked?size=1", token, null).getBody();
        assertThat(items(page)).hasSize(1);
        assertThat(workflowId(items(page).getFirst())).isEqualTo(second.toString());
        assertThat(page.get("meta")).isEqualTo(Map.of("total", 2, "page", 0, "size", 1));

        assertThat(call(HttpMethod.PUT, WORKFLOWS + first + "/bookmark", token, Map.of("bookmarked", false))
                .getBody()).isEqualTo(Map.of("bookmarked", false));
        Map<?, ?> after = call(HttpMethod.GET, WORKFLOWS + "bookmarked", token, null).getBody();
        assertThat(items(after)).extracting(WorkflowReviewEndpointsIT::workflowId).containsExactly(second.toString());

        for (String query : List.of("size=0", "size=51", "page=-1", "page=abc")) {
            ResponseEntity<Map> refused = call(HttpMethod.GET, WORKFLOWS + "bookmarked?" + query, token, null);
            assertThat(refused.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((Map<String, ?>) refused.getBody().get("errors")).as(query).hasSize(1);
        }
    }

    @Test
    void aSavedItemOfANonOwnersWorkflowEqualsItsItemInTheWorkflowList() {
        UserView ana = newUser("bm-eq-ana");
        UserView ben = member(ana, "bm-eq-ben");
        UUID workflowId = publicWorkflow(ana);
        String token = loginAs(ben);
        call(HttpMethod.PUT, WORKFLOWS + workflowId + "/bookmark", token, Map.of("bookmarked", true));

        Map<?, ?> saved = items(call(HttpMethod.GET, WORKFLOWS + "bookmarked", token, null).getBody()).getFirst();
        ResponseEntity<List> all = restTemplate.exchange(WORKFLOWS.substring(0, WORKFLOWS.length() - 1),
                HttpMethod.GET, new HttpEntity<>(null, bearer(token)), List.class);
        Object listed = all.getBody().stream()
                .filter(item -> workflowId.toString().equals(((Map<?, ?>) item).get("id"))).findFirst().orElseThrow();

        assertThat(saved.get("workflow")).isEqualTo(listed);
        assertThat(saved.get("bookmarkedAt")).isNotNull();
    }

    @Test
    void malformedFieldTypesAre400NamingTheField() {
        UserView ana = newUser("bad-ana");
        UserView ben = member(ana, "bad-ben");
        UUID workflowId = publicWorkflow(ana);
        completedRun(ben, workflowId);
        String token = loginAs(ben);
        UUID anyReview = UUID.randomUUID();

        assertNamed(callJson(HttpMethod.PUT, WORKFLOWS + workflowId + "/bookmark", token,
                "{\"bookmarked\":\"yes\"}"), "bookmarked");
        assertNamed(callJson(HttpMethod.PUT, WORKFLOWS + workflowId + "/bookmark", token, "{}"), "bookmarked");
        assertNamed(callJson(HttpMethod.PUT, WORKFLOWS + workflowId + "/reviews/" + anyReview + "/helpful", token,
                "{\"helpful\":\"yes\"}"), "helpful");
        assertNamed(callJson(HttpMethod.PUT, WORKFLOWS + workflowId + "/reviews/mine", token,
                "{\"rating\":4,\"images\":[\"x\"]}"), "images");
        assertNamed(callJson(HttpMethod.PUT, WORKFLOWS + workflowId + "/reviews/mine", token,
                "{\"rating\":\"yes\"}"), "rating");
        for (String notANumber : List.of("\"5\"", "true", "[4]", "{}")) {
            assertNamed(callJson(HttpMethod.PUT, WORKFLOWS + workflowId + "/reviews/mine", token,
                    "{\"rating\":" + notANumber + "}"), "rating");
        }

        assertThat(count("workflow_bookmark", workflowId)).isZero();
        assertThat(count("workflow_review", workflowId)).isZero();
    }

    @Test
    void aWriteRejectionIsNeverA400UploadIds() {
        UserView ana = newUser("rej-ana");
        UserView ben = member(ana, "rej-ben");
        UUID workflowId = publicWorkflow(ana);
        completedRun(ben, workflowId);
        String token = loginAs(ben);
        byte[] text = "not an image at all".getBytes(StandardCharsets.UTF_8);
        UUID upload = uploaded(ben, token, workflowId, "photo.png", text);

        ResponseEntity<Map> refused = call(HttpMethod.PUT, WORKFLOWS + workflowId + "/reviews/mine", token,
                Map.of("rating", 4, "images", List.of(upload)));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((Map<String, ?>) refused.getBody().get("errors")).containsKey("images")
                .doesNotContainKey("uploadIds");
        assertThat(count("workflow_review", workflowId)).isZero();

        ResponseEntity<Map> grant = call(HttpMethod.POST, WORKFLOWS + workflowId + "/reviews/image-uploads", token,
                Map.of("fileName", "clip.mp4", "size", 1000));
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, ?>) grant.getBody().get("errors")).containsKey("fileName");
    }

    @Test
    void bookmarkAndReviewRoutesAnswer404ForAPrivateWorkflowAnd401Anonymously() {
        UserView ana = newUser("hid-ana");
        UserView ben = member(ana, "hid-ben");
        UUID hidden = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        UUID visible = publicWorkflow(ana);
        String token = loginAs(ben);
        UUID review = UUID.randomUUID();

        List<Object[]> routes = List.of(
                new Object[] {HttpMethod.PUT, "/bookmark", Map.of("bookmarked", true)},
                new Object[] {HttpMethod.GET, "/bookmark", null},
                new Object[] {HttpMethod.PUT, "/reviews/mine", Map.of("rating", 4)},
                new Object[] {HttpMethod.GET, "/reviews/mine", null},
                new Object[] {HttpMethod.DELETE, "/reviews/mine", null},
                new Object[] {HttpMethod.GET, "/reviews", null},
                new Object[] {HttpMethod.GET, "/reviews/summary", null},
                new Object[] {HttpMethod.PUT, "/reviews/" + review + "/helpful", Map.of("helpful", true)},
                new Object[] {HttpMethod.POST, "/reviews/" + review + "/report", Map.of("reason", "spam")},
                new Object[] {HttpMethod.POST, "/reviews/image-uploads", Map.of("fileName", "a.png", "size", 10)});
        for (Object[] route : routes) {
            HttpMethod method = (HttpMethod) route[0];
            String suffix = (String) route[1];
            assertThat(call(method, WORKFLOWS + hidden + suffix, token, route[2]).getStatusCode())
                    .as("private " + method + " " + suffix).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(call(method, WORKFLOWS + visible + suffix, null, route[2]).getStatusCode())
                    .as("anonymous " + method + " " + suffix).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(call(HttpMethod.GET, WORKFLOWS + "bookmarked", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("workflow_bookmark", hidden)).isZero();
    }

    @Test
    void aMemberWithACompletedRunReviewsAndReadsTheSummary() {
        UserView ana = newUser("rv-ana");
        UserView ben = member(ana, "rv-ben");
        UserView chi = member(ana, "rv-chi");
        UUID workflowId = publicWorkflow(ana);
        String benToken = loginAs(ben);
        String path = WORKFLOWS + workflowId + "/reviews/mine";

        ResponseEntity<Map> early = call(HttpMethod.PUT, path, benToken, Map.of("rating", 4));
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, path, benToken, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(call(HttpMethod.PUT, path, loginAs(ana), Map.of("rating", 5)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        completedRun(ben, workflowId);
        ResponseEntity<Map> first = call(HttpMethod.PUT, path, benToken, Map.of("rating", 4, "body", "  Fast  "));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("rating", 4).containsEntry("body", "Fast")
                .containsEntry("helpfulCount", 0).containsEntry("images", List.of());

        ResponseEntity<Map> replaced = call(HttpMethod.PUT, path, benToken, Map.of("rating", 5));
        assertThat(replaced.getBody()).containsEntry("id", first.getBody().get("id"))
                .containsEntry("createdAt", first.getBody().get("createdAt")).containsEntry("rating", 5);
        assertThat(replaced.getBody().get("body")).isNull();

        for (Object bad : List.of(0, 6, 4.5)) {
            ResponseEntity<Map> refused = call(HttpMethod.PUT, path, benToken, Map.of("rating", bad));
            assertThat(refused.getStatusCode()).as(String.valueOf(bad)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat((Map<String, ?>) refused.getBody().get("errors")).containsKey("rating");
        }
        // A decimal that only becomes whole once read as a double must still be refused.
        for (String bad : List.of("4.0000000000000000001", "null")) {
            ResponseEntity<Map> refused = callJson(HttpMethod.PUT, path, benToken, "{\"rating\":" + bad + "}");
            assertThat(refused.getStatusCode()).as(bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat((Map<String, ?>) refused.getBody().get("errors")).as(bad).containsKey("rating");
        }
        assertThat(call(HttpMethod.PUT, path, benToken, Map.of("body", "no rating")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        completedRun(chi, workflowId);
        call(HttpMethod.PUT, WORKFLOWS + workflowId + "/reviews/mine", loginAs(chi), Map.of("rating", 4));
        Map<String, Object> summary = call(HttpMethod.GET, WORKFLOWS + workflowId + "/reviews/summary", benToken, null)
                .getBody();
        assertThat(summary).containsEntry("total", 2).containsEntry("average", 4.5);
        assertThat(summary.get("distribution")).isEqualTo(Map.of("1", 0, "2", 0, "3", 0, "4", 1, "5", 1));

        assertThat(call(HttpMethod.DELETE, path, benToken, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.DELETE, path, benToken, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, WORKFLOWS + workflowId + "/reviews/summary", benToken, null).getBody())
                .containsEntry("total", 1);
    }

    @Test
    void anotherMemberMarksHelpfulAndSortsByHelpful() {
        UserView ana = newUser("hp-ana");
        UserView ben = member(ana, "hp-ben");
        UserView chi = member(ana, "hp-chi");
        UserView dan = member(ana, "hp-dan");
        UUID workflowId = publicWorkflow(ana);
        for (UserView reviewer : List.of(ben, chi)) {
            completedRun(reviewer, workflowId);
        }
        String benToken = loginAs(ben);
        String chiToken = loginAs(chi);
        String reviews = WORKFLOWS + workflowId + "/reviews";
        String benReview = (String) call(HttpMethod.PUT, reviews + "/mine", benToken, Map.of("rating", 3)).getBody()
                .get("id");
        call(HttpMethod.PUT, reviews + "/mine", chiToken, Map.of("rating", 5, "body", "great"));

        assertThat(call(HttpMethod.PUT, reviews + "/" + benReview + "/helpful", benToken, Map.of("helpful", true))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, reviews + "/" + benReview + "/report", benToken, Map.of("reason", "spam"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String chiMark = reviews + "/" + benReview + "/helpful";
        assertThat(call(HttpMethod.PUT, chiMark, chiToken, Map.of("helpful", true)).getBody())
                .isEqualTo(Map.of("helpful", true, "helpfulCount", 1));
        assertThat(call(HttpMethod.PUT, chiMark, chiToken, Map.of("helpful", true)).getBody())
                .isEqualTo(Map.of("helpful", true, "helpfulCount", 1));
        assertThat(call(HttpMethod.PUT, chiMark, loginAs(dan), Map.of("helpful", true)).getBody())
                .isEqualTo(Map.of("helpful", true, "helpfulCount", 2));

        Map<?, ?> byHelpful = call(HttpMethod.GET, reviews + "?sort=helpful", chiToken, null).getBody();
        assertThat(items(byHelpful).getFirst().get("id")).isEqualTo(benReview);
        Map<String, Object> first = items(byHelpful).getFirst();
        assertThat(first).containsEntry("helpfulByMe", true).containsEntry("mine", false)
                .containsEntry("authorName", "hp-ben");
        assertThat(first.toString()).doesNotContain(ben.id().toString()).doesNotContain(ben.email());
        assertThat(items(call(HttpMethod.GET, reviews + "?withComment=true&rating=5", chiToken, null).getBody()))
                .hasSize(1);

        ResponseEntity<Map> report = call(HttpMethod.POST, reviews + "/" + benReview + "/report", chiToken,
                Map.of("reason", "spam", "details", "link farm"));
        assertThat(report.getBody()).isEqualTo(Map.of("reported", true));
        assertThat(call(HttpMethod.POST, reviews + "/" + benReview + "/report", chiToken, Map.of("reason", "rude"))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<Map> nulDetails = call(HttpMethod.POST, reviews + "/" + benReview + "/report",
                loginAs(dan), Map.of("reason", "spam", "details", "a\u0000b"));
        assertThat(nulDetails.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((Map<String, ?>) nulDetails.getBody().get("errors")).containsKey("details");
        ResponseEntity<Map> nulBody = call(HttpMethod.PUT, reviews + "/mine", chiToken,
                Map.of("rating", 4, "body", "a\u0000b"));
        assertThat(nulBody.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((Map<String, ?>) nulBody.getBody().get("errors")).containsKey("body");

        for (String query : List.of("sort=oldest", "rating=6", "size=0", "size=51")) {
            assertThat(call(HttpMethod.GET, reviews + "?" + query, chiToken, null).getStatusCode()).as(query)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void aReviewWithImagesListsSignedLinksToItsStoredCopies() throws Exception {
        UserView ana = newUser("img-ana");
        UserView ben = member(ana, "img-ben");
        UserView chi = member(ana, "img-chi");
        UUID workflowId = publicWorkflow(ana);
        completedRun(ben, workflowId);
        String token = loginAs(ben);
        UUID upload = uploaded(ben, token, workflowId, "photo.png", png());

        ResponseEntity<Map> written = call(HttpMethod.PUT, WORKFLOWS + workflowId + "/reviews/mine", token,
                Map.of("rating", 5, "images", List.of(upload)));

        assertThat(written.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> image = (Map<?, ?>) ((List<?>) written.getBody().get("images")).getFirst();
        String storedKey = jdbc.queryForObject("SELECT object_key FROM workflow_review_image WHERE id = ?::uuid",
                String.class, image.get("id"));
        assertThat(storedKey).startsWith("workflow-review/" + workflowId + "/" + ben.id() + "/");

        Map<?, ?> listed = items(call(HttpMethod.GET, WORKFLOWS + workflowId + "/reviews", loginAs(chi), null)
                .getBody()).getFirst();
        Map<?, ?> listedImage = (Map<?, ?>) ((List<?>) listed.get("images")).getFirst();
        assertThat(listedImage.get("id")).isEqualTo(image.get("id"));
        HttpResponse<byte[]> fetched = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create((String) listedImage.get("url"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isNotEmpty();

        ResponseEntity<Map> dropped = call(HttpMethod.PUT, WORKFLOWS + workflowId + "/reviews/mine", token,
                Map.of("rating", 5, "images", List.of()));
        assertThat((List<?>) dropped.getBody().get("images")).isEmpty();
    }

    @Test
    void aReviewIdOfAnotherWorkflowIs404() {
        UserView ana = newUser("x-ana");
        UserView ben = member(ana, "x-ben");
        UserView chi = member(ana, "x-chi");
        UUID mine = publicWorkflow(ana);
        UUID other = publicWorkflow(ana);
        completedRun(ben, mine);
        String review = (String) call(HttpMethod.PUT, WORKFLOWS + mine + "/reviews/mine", loginAs(ben),
                Map.of("rating", 4)).getBody().get("id");
        String chiToken = loginAs(chi);

        assertThat(call(HttpMethod.PUT, WORKFLOWS + other + "/reviews/" + review + "/helpful", chiToken,
                Map.of("helpful", true)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, WORKFLOWS + other + "/reviews/" + review + "/report", chiToken,
                Map.of("reason", "spam")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT, WORKFLOWS + mine + "/reviews/" + UUID.randomUUID() + "/helpful", chiToken,
                Map.of("helpful", true)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private UUID publicWorkflow(UserView owner) {
        return newWorkflow(owner.id(), true, new NewWorkflowStep("only", "echo", null)).id();
    }

    private UserView member(UserView admin, String prefix) {
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", prefix, "MEMBER");
    }

    private void completedRun(UserView user, UUID workflowId) {
        RunView run = workflowRunService.startRun(user.id(), workflowId, "hello");
        assertThat(awaitTerminal(user.id(), run.id()).status()).isEqualTo("completed");
    }

    private UUID uploaded(UserView user, String token, UUID workflowId, String fileName, byte[] body) {
        ResponseEntity<Map> grant = call(HttpMethod.POST, WORKFLOWS + workflowId + "/reviews/image-uploads", token,
                Map.of("fileName", fileName, "size", body.length));
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID uploadId = UUID.fromString((String) grant.getBody().get("uploadId"));
        try (S3Client store = objectStore()) {
            store.putObject(o -> o.bucket(UPLOAD_BUCKET).key("review-image/" + user.id() + "/" + uploadId)
                    .contentType("image/png"), RequestBody.fromBytes(body));
        }
        return uploadId;
    }

    private static byte[] png() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String table, UUID workflowId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE workflow_id = ?", Long.class,
                workflowId);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<?, ?> page) {
        return ((List<Map<String, Object>>) page.get("data"));
    }

    private static String workflowId(Map<?, ?> item) {
        return (String) ((Map<?, ?>) item.get("workflow")).get("id");
    }

    private static void assertNamed(ResponseEntity<Map> response, String field) {
        assertThat(response.getStatusCode()).as(field).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, ?>) response.getBody().get("errors")).as(field).containsKey(field);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, bearer(token)), Map.class);
    }

    private ResponseEntity<Map> callJson(HttpMethod method, String path, String token, String json) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(json, headers), Map.class);
    }
}
