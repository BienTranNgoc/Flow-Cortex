package com.flowcortex.app.trend;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.node.ObjectNode;

/** The trend taxonomy admin routes over HTTP against a real database. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TrendTaxonomyAdminIT extends AbstractPostgresBootTest {

    private static final String PASSWORD = "correct-password-1";
    private static final String TAXONOMY = "/api/v1/admin/trend/taxonomy";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> STARTING_KEYS = List.of("nguoi", "san-pham", "thu-cung", "nhay-chuyen-canh");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    private UserView owner;
    private UserView editor;
    private String token;

    @BeforeEach
    void arrange() {
        resetTaxonomy();
        owner = userService.createInitialAdmin(email("owner"), PASSWORD, "Owner");
        editor = userService.createUser(owner.id(), email("editor"), PASSWORD, "Editor", "CONTENT_EDITOR");
        token = login(editor.email());
    }

    @AfterEach
    void cleanUp() {
        resetTaxonomy();
    }

    private void resetTaxonomy() {
        jdbc.update("DELETE FROM trend_tag");
        jdbc.update("DELETE FROM trend_category");
        jdbc.update("INSERT INTO trend_category (key, name_vi, name_en, display_order) VALUES"
                + " ('nguoi', 'Người', 'People', 0), ('san-pham', 'Sản phẩm', 'Products', 1),"
                + " ('thu-cung', 'Thú cưng', 'Pets', 2),"
                + " ('nhay-chuyen-canh', 'Nhảy và chuyển cảnh', 'Dance and transitions', 3)");
    }

    @Test
    void freshInstallListsTheFourStartingCategories() {
        ResponseEntity<JsonNode> response = call(HttpMethod.GET, TAXONOMY, token, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode categories = response.getBody().get("categories");
        assertThat(keys(categories)).containsExactlyElementsOf(STARTING_KEYS);
        for (int i = 0; i < 4; i++) {
            assertThat(categories.get(i).get("order").asInt()).isEqualTo(i);
            assertThat(categories.get(i).get("visible").asBoolean()).isTrue();
            assertThat(categories.get(i).get("usageCount").asInt()).isZero();
        }
        assertThat(categories.get(0).get("nameVi").asString()).isEqualTo("Người");
        assertThat(categories.get(0).get("nameEn").asString()).isEqualTo("People");
        assertThat(categories.get(3).get("nameEn").asString()).isEqualTo("Dance and transitions");
        assertThat(response.getBody().get("tags")).isEmpty();
    }

    @Test
    void createAnswers201WithTheDerivedKey() {
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, TAXONOMY + "/categories", token,
                names("  Đồ ăn & Thức uống  ", "Food and drinks"));
        ResponseEntity<JsonNode> again = call(HttpMethod.POST, TAXONOMY + "/categories", token,
                names("Đồ ăn & Thức uống", "Food again"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = created.getBody();
        assertThat(body.get("key").asString()).isEqualTo("do-an-thuc-uong");
        assertThat(body.get("nameVi").asString()).isEqualTo("Đồ ăn & Thức uống");
        assertThat(body.get("nameEn").asString()).isEqualTo("Food and drinks");
        assertThat(body.get("order").asInt()).isEqualTo(4);
        assertThat(body.get("visible").asBoolean()).isTrue();
        assertThat(body.get("usageCount").asInt()).isZero();
        assertThat(again.getBody().get("key").asString()).isEqualTo("do-an-thuc-uong-2");
        assertThat(keys(call(HttpMethod.GET, TAXONOMY, token, null).getBody().get("categories")))
                .endsWith("do-an-thuc-uong", "do-an-thuc-uong-2");
    }

    @Test
    void aNameWithNoLetterOrDigitIs400AndStoresNothing() {
        ResponseEntity<JsonNode> response = call(HttpMethod.POST, TAXONOMY + "/categories", token,
                names("!!!", "Symbols"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asString()).isEqualTo("validation-failed");
        assertThat(response.getBody().get("errors").has("nameVi")).isTrue();
        assertThat(count("trend_category")).isEqualTo(4);
    }

    @Test
    void nameOverTheLimitIs400() {
        ResponseEntity<JsonNode> category = call(HttpMethod.POST, TAXONOMY + "/categories", token,
                names("Hợp lệ", "e".repeat(61)));
        ResponseEntity<JsonNode> tag = call(HttpMethod.POST, TAXONOMY + "/tags", token,
                names("v".repeat(31), "Valid"));

        assertThat(category.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(category.getBody().get("errors").has("nameEn")).isTrue();
        assertThat(tag.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tag.getBody().get("errors").has("nameVi")).isTrue();
        assertThat(count("trend_category")).isEqualTo(4);
        assertThat(count("trend_tag")).isZero();
    }

    @Test
    void hideKeepsNames() {
        ObjectNode body = JSON.createObjectNode();
        body.put("visible", false);

        ResponseEntity<JsonNode> response = call(HttpMethod.PATCH, TAXONOMY + "/categories/san-pham", token, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("visible").asBoolean()).isFalse();
        assertThat(response.getBody().get("nameVi").asString()).isEqualTo("Sản phẩm");
        assertThat(response.getBody().get("nameEn").asString()).isEqualTo("Products");
    }

    @Test
    void renameKeepsTheKey() {
        ResponseEntity<JsonNode> response = call(HttpMethod.PATCH, TAXONOMY + "/categories/thu-cung", token,
                names("Vật nuôi", "Animals"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("key").asString()).isEqualTo("thu-cung");
        assertThat(response.getBody().get("nameVi").asString()).isEqualTo("Vật nuôi");
        assertThat(response.getBody().get("visible").asBoolean()).isTrue();
    }

    @Test
    void reorderAppliesAndAStaleListIs409() {
        ObjectNode reversed = JSON.createObjectNode();
        reversed.set("keys", JSON.valueToTree(STARTING_KEYS.reversed()));

        ResponseEntity<JsonNode> applied = call(HttpMethod.PUT, TAXONOMY + "/categories/order", token, reversed);

        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(keys(applied.getBody())).containsExactlyElementsOf(STARTING_KEYS.reversed());
        assertThat(applied.getBody().get(0).get("order").asInt()).isZero();
        assertThat(applied.getBody().get(3).get("key").asString()).isEqualTo("nguoi");
        assertThat(applied.getBody().get(3).get("order").asInt()).isEqualTo(3);

        call(HttpMethod.POST, TAXONOMY + "/categories", token, names("Mới thêm", "Added meanwhile"));
        ResponseEntity<JsonNode> stale = call(HttpMethod.PUT, TAXONOMY + "/categories/order", token, reversed);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("taxonomy-order-stale");
        assertThat(keys(call(HttpMethod.GET, TAXONOMY, token, null).getBody().get("categories")))
                .containsExactly("nhay-chuyen-canh", "thu-cung", "san-pham", "nguoi", "moi-them");
    }

    @Test
    void tagsCanBeCreatedRenamedReorderedAndDeleted() {
        String first = id(call(HttpMethod.POST, TAXONOMY + "/tags", token, names("Hài hước", "Funny")));
        String second = id(call(HttpMethod.POST, TAXONOMY + "/tags", token, names("Dễ thương", "Cute")));
        ObjectNode swapped = JSON.createObjectNode();
        swapped.set("ids", JSON.valueToTree(List.of(second, first)));
        ObjectNode hide = JSON.createObjectNode();
        hide.put("visible", false);

        ResponseEntity<JsonNode> reordered = call(HttpMethod.PUT, TAXONOMY + "/tags/order", token, swapped);
        ResponseEntity<JsonNode> hidden = call(HttpMethod.PATCH, TAXONOMY + "/tags/" + first, token, hide);
        ResponseEntity<JsonNode> removed = call(HttpMethod.DELETE, TAXONOMY + "/tags/" + second, token, null);

        assertThat(reordered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reordered.getBody().get(0).get("id").asString()).isEqualTo(second);
        assertThat(hidden.getBody().get("visible").asBoolean()).isFalse();
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode tags = call(HttpMethod.GET, TAXONOMY, token, null).getBody().get("tags");
        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).get("id").asString()).isEqualTo(first);
        assertThat(tags.get(0).get("order").asInt()).isEqualTo(1);
    }

    @Test
    void anUnusedCategoryIsDeleted() {
        ResponseEntity<JsonNode> removed = call(HttpMethod.DELETE, TAXONOMY + "/categories/thu-cung", token, null);

        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(keys(call(HttpMethod.GET, TAXONOMY, token, null).getBody().get("categories")))
                .containsExactly("nguoi", "san-pham", "nhay-chuyen-canh");
    }

    @Test
    void unknownRecordsAre404WithAVietnameseDetailOnRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        ObjectNode hide = JSON.createObjectNode();
        hide.put("visible", false);

        ResponseEntity<JsonNode> category = restTemplate.exchange(TAXONOMY + "/categories/khong-co",
                HttpMethod.PATCH, new HttpEntity<>(hide, headers), JsonNode.class);
        ResponseEntity<JsonNode> tag = call(HttpMethod.PATCH, TAXONOMY + "/tags/" + UUID.randomUUID(), token, hide);

        assertThat(category.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(category.getBody().get("code").asString()).isEqualTo("category-not-found");
        assertThat(category.getBody().get("detail").asString()).isEqualTo("Không có danh mục trend khong-co");
        assertThat(tag.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tag.getBody().get("code").asString()).isEqualTo("tag-not-found");
    }

    @Test
    void viewOnlyStaffCannotWrite() {
        UserView viewer = userService.createUser(owner.id(), email("viewer"), PASSWORD, "Viewer", "CUSTOMER_SUPPORT");
        jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES ('CUSTOMER_SUPPORT', ?)",
                Permission.CONTENT_TREND_TEMPLATE_VIEW.key());
        try {
            String viewerToken = login(viewer.email());

            ResponseEntity<JsonNode> list = call(HttpMethod.GET, TAXONOMY, viewerToken, null);
            ResponseEntity<JsonNode> write = call(HttpMethod.POST, TAXONOMY + "/tags", viewerToken,
                    names("Hài hước", "Funny"));

            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(count("trend_tag")).isZero();
        } finally {
            jdbc.update("DELETE FROM role_permission WHERE role_code = 'CUSTOMER_SUPPORT' AND permission_key = ?",
                    Permission.CONTENT_TREND_TEMPLATE_VIEW.key());
        }
    }

    @Test
    void aWriteLeavesOneAuditRow() {
        ObjectNode hide = JSON.createObjectNode();
        hide.put("visible", false);

        ResponseEntity<JsonNode> response = call(HttpMethod.PATCH, TAXONOMY + "/categories/san-pham", token, hide);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT resource_type, resource_id, status, change_summary::text AS summary FROM admin_audit_event"
                        + " WHERE actor_user_id = ? AND action = 'trend_category.update'", editor.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("resource_type")).isEqualTo("trend_category");
        assertThat(rows.get(0).get("resource_id")).isEqualTo("san-pham");
        assertThat(rows.get(0).get("status")).isEqualTo("success");
        assertThat(JSON.readTree((String) rows.get(0).get("summary")))
                .isEqualTo(JSON.readTree("{\"visible\":false}"));
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String bearer, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static ObjectNode names(String nameVi, String nameEn) {
        ObjectNode body = JSON.createObjectNode();
        body.put("nameVi", nameVi);
        body.put("nameEn", nameEn);
        return body;
    }

    private static List<String> keys(JsonNode records) {
        List<String> keys = new ArrayList<>();
        for (JsonNode record : records) {
            keys.add(record.get("key").asString());
        }
        return keys;
    }

    private static String id(ResponseEntity<JsonNode> created) {
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asString();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String login(String email) {
        JsonNode response = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD), JsonNode.class).getBody();
        return response.get("accessToken").asString();
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
