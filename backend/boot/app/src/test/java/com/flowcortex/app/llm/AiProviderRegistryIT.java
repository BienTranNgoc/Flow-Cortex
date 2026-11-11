package com.flowcortex.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The model registry and provider overview against the real, fully-wired app: what the database enforces
 * (one row per id, even under a race), who may call what, and that no setting value leaves the server.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.llm.gemini.api-key=" + AiProviderRegistryIT.API_KEY)
@AutoConfigureTestRestTemplate
class AiProviderRegistryIT extends AbstractPostgresBootTest {

    static final String API_KEY = "test-key-123";
    private static final String PASSWORD = "correct-password-1";
    private static final String MODELS = "/api/v1/admin/ai/models";

    @Autowired
    private UserService userService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyRegistry() {
        jdbc.update("DELETE FROM llm_model");
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private String loginAs(UserView user) {
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("email", user.email(), "password", PASSWORD), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("accessToken");
    }

    private String adminToken(String label) {
        return loginAs(seedAdmin(label));
    }

    private String staffToken(String label, String role) {
        UserView admin = seedAdmin(label + "-owner");
        return loginAs(userService.createUser(admin.id(), uniqueEmail(label), PASSWORD, label, role));
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    private static Map<String, Object> entry(String modelKey, String displayName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelKey", modelKey);
        body.put("providerKey", "gemini");
        body.put("displayName", displayName);
        return body;
    }

    private static String uniqueModel() {
        return "gemini-it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> storedRow(String modelKey) {
        return jdbc.queryForMap("SELECT display_name, max_output_tokens, supports_vision, supports_audio, active,"
                + " display_order FROM llm_model WHERE model_key = ?", modelKey);
    }

    @Test
    void anAdminCreatesAnEntryWithDefaults() {
        String token = adminToken("create-defaults");
        String key = uniqueModel();

        ResponseEntity<Map> created = call(HttpMethod.POST, MODELS, token, entry(key, "Flash"), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("modelKey", key).containsEntry("maxOutputTokens", 4096)
                .containsEntry("supportsVision", false).containsEntry("supportsAudio", false)
                .containsEntry("active", true).containsEntry("displayOrder", 0);
        assertThat(storedRow(key)).containsEntry("display_name", "Flash");
    }

    @Test
    void aDuplicateIdIsConflictAndKeepsTheStoredEntry() {
        String token = adminToken("duplicate");
        String key = uniqueModel();
        call(HttpMethod.POST, MODELS, token, entry(key, "First"), Map.class);

        ResponseEntity<Map> second = call(HttpMethod.POST, MODELS, token, entry(key, "Second"), Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("code", "llm-model-exists");
        assertThat(storedRow(key)).containsEntry("display_name", "First");
    }

    @Test
    void twoCreatesOfOneIdAtOnceGiveOneWinnerAndOneRow() throws Exception {
        String token = adminToken("race");
        String key = uniqueModel();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<ResponseEntity<Map>>> calls = new ArrayList<>();
        for (String name : List.of("Winner A", "Winner B")) {
            calls.add(() -> {
                start.await();
                return call(HttpMethod.POST, MODELS, token, entry(key, name), Map.class);
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<Map>> call : calls) {
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            Map<HttpStatus, Map> bodies = new LinkedHashMap<>();
            for (Future<ResponseEntity<Map>> future : futures) {
                ResponseEntity<Map> response = future.get();
                HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
                statuses.add(status);
                bodies.put(status, response.getBody());
            }

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_model WHERE model_key = ?", Integer.class, key))
                    .isEqualTo(1);
            assertThat(storedRow(key)).containsEntry("display_name", bodies.get(HttpStatus.CREATED).get("displayName"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anIdTheProviderDoesNotAcceptIsBadRequestOnModelKey() {
        String token = adminToken("bad-id");

        ResponseEntity<Map> response = call(HttpMethod.POST, MODELS, token, entry("gpt-5", "Not Gemini"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "llm-model-invalid");
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("modelKey");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_model", Integer.class)).isZero();
    }

    @Test
    void anOutOfRangeDisplayOrderIsBadRequestOnThatField() {
        String token = adminToken("bad-order");
        Map<String, Object> body = entry(uniqueModel(), "Out of range");
        body.put("displayOrder", 40000);

        ResponseEntity<Map> response = call(HttpMethod.POST, MODELS, token, body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("displayOrder");
    }

    @Test
    void theListOrdersByDisplayOrderThenIdAndIncludesInactiveEntries() {
        String token = adminToken("list");
        Map<String, Object> later = entry("gemini-it-b", "Later");
        later.put("displayOrder", 5);
        Map<String, Object> inactive = entry("gemini-it-a", "Inactive");
        inactive.put("displayOrder", 5);
        inactive.put("active", false);
        Map<String, Object> first = entry("gemini-it-z", "First");
        first.put("displayOrder", 1);
        for (Map<String, Object> body : List.of(later, inactive, first)) {
            assertThat(call(HttpMethod.POST, MODELS, token, body, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<Map> page = call(HttpMethod.GET, MODELS, token, null, Map.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.getBody().get("items");
        assertThat(items).extracting(item -> item.get("modelKey"))
                .containsExactly("gemini-it-z", "gemini-it-a", "gemini-it-b");
        assertThat(items.get(1)).containsEntry("active", false);
    }

    @Test
    void replacingWithActiveOffReadsBackInactive() {
        String token = adminToken("replace");
        String key = uniqueModel();
        call(HttpMethod.POST, MODELS, token, entry(key, "Before"), Map.class);
        Map<String, Object> body = entry(key, "After");
        body.put("active", false);

        ResponseEntity<Map> replaced = call(HttpMethod.PUT, MODELS + "/" + key, token, body, Map.class);
        ResponseEntity<Map> read = call(HttpMethod.GET, MODELS + "/" + key, token, null, Map.class);

        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).containsEntry("active", false).containsEntry("displayName", "After");
    }

    @Test
    void deletingAnUnknownIdIsNotFound() {
        String token = adminToken("delete-unknown");

        ResponseEntity<Map> response = call(HttpMethod.DELETE, MODELS + "/gemini-it-missing", token, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "llm-model-not-found");
    }

    @Test
    void aRoleWithNeitherKeyIsRefusedTheList() {
        String token = staffToken("support-list", "CUSTOMER_SUPPORT");

        ResponseEntity<String> response = call(HttpMethod.GET, MODELS, token, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aViewOnlyRoleReadsButCannotCreate() {
        String token = staffToken("support-view-only", "CUSTOMER_SUPPORT");
        jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES ('CUSTOMER_SUPPORT', ?)"
                + " ON CONFLICT DO NOTHING", Permission.AI_PROVIDER_VIEW.key());
        try {
            ResponseEntity<String> read = call(HttpMethod.GET, MODELS, token, null, String.class);
            ResponseEntity<String> write = call(HttpMethod.POST, MODELS, token, entry(uniqueModel(), "Nope"),
                    String.class);

            assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_model", Integer.class)).isZero();
        } finally {
            jdbc.update("DELETE FROM role_permission WHERE role_code = 'CUSTOMER_SUPPORT' AND permission_key = ?",
                    Permission.AI_PROVIDER_VIEW.key());
        }
    }

    @Test
    void theOverviewNamesSettingsButNeverCarriesTheirValues() {
        String token = adminToken("overview");

        ResponseEntity<String> response = call(HttpMethod.GET, "/api/v1/admin/ai/providers", token, null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("gemini").doesNotContain(API_KEY);
    }
}
