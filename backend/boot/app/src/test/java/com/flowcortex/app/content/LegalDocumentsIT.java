package com.flowcortex.app.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.content.document.PublishedDocuments;
import com.flowcortex.content.legal.LegalCatalogue;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The legal documents on the versioned document engine over HTTP against a real database: the registered set (the
 * application context only starts when every definition bean survives the module scan) and what the anonymous
 * index route answers as documents and the index are published.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class LegalDocumentsIT extends AbstractPostgresBootTest {

    private static final String PASSWORD = "correct-password-1";
    private static final String ADMIN = "/api/v1/admin/content/documents";
    private static final String PUBLIC_INDEX = "/api/v1/content/legal-index";
    private static final String PUBLIC_CACHE = "public, max-age=0, stale-while-revalidate=10";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PublishedDocuments publishedDocuments;

    private String token;

    @BeforeEach
    void arrange() {
        clearDocuments();
        UserView owner = userService.createInitialAdmin(email("owner"), PASSWORD, "Owner");
        UserView editor = userService.createUser(owner.id(), email("editor"), PASSWORD, "Editor", "CONTENT_EDITOR");
        token = login(editor.email());
    }

    @AfterEach
    void cleanUp() {
        clearDocuments();
    }

    private void clearDocuments() {
        jdbc.update("DELETE FROM content_document_revision WHERE slug LIKE 'legal-%'");
        jdbc.update("DELETE FROM content_document WHERE slug LIKE 'legal-%'");
        LegalCatalogue.slugs().forEach(publishedDocuments::invalidate);
        publishedDocuments.invalidate(LegalCatalogue.INDEX_SLUG);
    }

    @Test
    void theAdminListHoldsTheElevenDocumentsAndTheIndexInOrderWithTheirBounds() {
        JsonNode list = exchange(HttpMethod.GET, ADMIN, token, null).getBody();

        List<JsonNode> legal = new ArrayList<>();
        list.forEach(item -> {
            if (item.get("slug").asString().startsWith("legal-")) {
                legal.add(item);
            }
        });
        List<String> expected = new ArrayList<>(LegalCatalogue.slugs());
        expected.add(LegalCatalogue.INDEX_SLUG);
        assertThat(legal).extracting(item -> item.get("slug").asString()).containsExactlyElementsOf(expected);
        assertThat(legal).allSatisfy(item -> {
            assertThat(item.get("minSections").asInt()).isEqualTo(1);
            assertThat(item.get("maxSections").asInt()).isEqualTo(1);
        });
        assertThat(legal.get(0).get("publicHistory").asBoolean()).isTrue();
        assertThat(legal.get(0).get("sectionTypes")).extracting(JsonNode::asString).containsExactly("legalDoc");
        assertThat(legal.get(11).get("publicHistory").asBoolean()).isFalse();
        assertThat(legal.get(11).get("sectionTypes")).extracting(JsonNode::asString).containsExactly("legalIndex");
    }

    @Test
    void theIndexRouteAnswersEmptyThenFollowsPublicationsAndTheIndex() {
        ResponseEntity<JsonNode> empty = get(PUBLIC_INDEX);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(empty.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo(PUBLIC_CACHE);
        assertThat(empty.getBody().get("items")).isEmpty();

        publishDocument("legal-terms", "Điều khoản", "Terms");
        publishDocument("legal-privacy", "Bảo mật", "Privacy");
        assertThat(slugs(get(PUBLIC_INDEX).getBody())).containsExactly("legal-privacy", "legal-terms");

        publishIndex(item("legal-terms", true), item("legal-privacy", false), item("legal-pricing", true));
        JsonNode resolved = get(PUBLIC_INDEX).getBody();
        assertThat(slugs(resolved)).containsExactly("legal-terms");
        assertThat(resolved.get("items").get(0).get("path").asString()).isEqualTo("/legal/terms");
        assertThat(resolved.get("items").get(0).get("label").get("en").asString()).isEqualTo("Terms");

        assertThat(get("/api/v1/content/documents/legal-index/history").getBody().get("items")).isEmpty();
        assertThat(get("/api/v1/content/documents/legal-terms/history").getBody().get("items")).hasSize(1);
    }

    private void publishDocument(String slug, String titleVi, String titleEn) {
        assertThat(save(slug, 0, JSON.createArrayNode().add(legalDoc(titleVi, titleEn, "1.0", "2026-12-01")))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publish(slug, 1).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void publishIndex(ObjectNode... items) {
        assertThat(save(LegalCatalogue.INDEX_SLUG, 0, JSON.createArrayNode().add(legalIndex(items))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(publish(LegalCatalogue.INDEX_SLUG, 1).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<JsonNode> save(String slug, int version, JsonNode sections) {
        ObjectNode body = JSON.createObjectNode();
        body.put("version", version);
        body.set("sections", sections);
        body.set("seoMeta", seo());
        return exchange(HttpMethod.PUT, ADMIN + "/" + slug, token, body);
    }

    private ResponseEntity<JsonNode> publish(String slug, int version) {
        ObjectNode body = JSON.createObjectNode();
        body.put("version", version);
        return exchange(HttpMethod.POST, ADMIN + "/" + slug + "/publish", token, body);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return exchange(HttpMethod.GET, path, null, null);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String bearer, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static List<String> slugs(JsonNode resolved) {
        List<String> slugs = new ArrayList<>();
        resolved.get("items").forEach(item -> slugs.add(item.get("slug").asString()));
        return slugs;
    }

    private static ObjectNode legalDoc(String titleVi, String titleEn, String docVersion, String effectiveDate) {
        ObjectNode data = JSON.createObjectNode();
        data.set("title", text(titleVi, titleEn));
        data.set("body", text("Nội dung", "Body"));
        data.put("docVersion", docVersion);
        data.put("effectiveDate", effectiveDate);
        return section("legalDoc", data);
    }

    private static ObjectNode legalIndex(ObjectNode... items) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode item : items) {
            array.add(item);
        }
        ObjectNode data = JSON.createObjectNode();
        data.set("items", array);
        return section("legalIndex", data);
    }

    private static ObjectNode item(String slug, boolean visible) {
        ObjectNode item = JSON.createObjectNode();
        item.put("slug", slug);
        item.put("visible", visible);
        return item;
    }

    private static ObjectNode section(String type, ObjectNode data) {
        ObjectNode section = JSON.createObjectNode();
        section.put("type", type);
        section.put("schemaVersion", 1);
        section.set("data", data);
        return section;
    }

    private static ObjectNode seo() {
        ObjectNode seo = JSON.createObjectNode();
        seo.set("title", text("Tiêu đề", "Title"));
        seo.set("description", text("Mô tả", "Description"));
        return seo;
    }

    private static ObjectNode text(String vi, String en) {
        ObjectNode text = JSON.createObjectNode();
        text.put("vi", vi);
        text.put("en", en);
        return text;
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
