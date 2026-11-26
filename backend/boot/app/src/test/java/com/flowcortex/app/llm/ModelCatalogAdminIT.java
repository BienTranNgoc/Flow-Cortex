package com.flowcortex.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.llm.api.ModelDescriptor;
import com.flowcortex.llm.api.ModelDescriptor.Knob;
import com.flowcortex.llm.api.ModelDescriptor.Limits;
import com.flowcortex.llm.api.ModelDescriptor.Modality;
import com.flowcortex.llm.api.ModelDescriptor.Status;
import com.flowcortex.llm.api.ModelDescriptor.StructuredOutput;
import com.flowcortex.llm.api.ModelDescriptor.Thinking;
import com.flowcortex.llm.api.ModelDescriptors;
import com.flowcortex.llm.descriptor.SeededModelDescriptors;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The capability and model catalog against the real, fully-wired app: the seeded rows, what the database
 * refuses, the admin routes and their problems, who may call what, and a create race.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.llm.gemini.api-key=catalog-it-key")
@AutoConfigureTestRestTemplate
class ModelCatalogAdminIT extends AbstractPostgresBootTest {

    private static final String PASSWORD = "correct-password-1";
    private static final String CAPABILITIES = "/api/v1/admin/ai/capabilities";
    private static final String DEPRECATED_ID = "gemini-it-deprecated";

    /** The catalog's expected rows: capability to category/group, from the seeded set of the migration. */
    private static final Map<String, String> SEEDED = Map.ofEntries(
            Map.entry("echo", "control/control"),
            Map.entry("fail", "control/control"),
            Map.entry("iterate", "control/control"),
            Map.entry("workflow.call", "control/control"),
            Map.entry("generate_script", "generate/generate"),
            Map.entry("generate_storyboard", "generate/generate"),
            Map.entry("generate_speech", "generate/generate"),
            Map.entry("generate_video", "generate/generate"),
            Map.entry("tts", "generate/generate"),
            Map.entry("probe", "analyze/analyze"),
            Map.entry("extract_audio", "transform/edit"),
            Map.entry("align_audio", "transform/edit"),
            Map.entry("render_video", "transform/edit"),
            Map.entry("separate_audio", "transform/edit"),
            Map.entry("translate", "transform/edit"),
            Map.entry("APPROVAL_CHECKPOINT", "review/control"),
            Map.entry("qa.review", "review/analyze"),
            Map.entry("fetch_video", "ingest/input"),
            Map.entry("ingest_video", "ingest/input"),
            Map.entry("ingest_image", "ingest/input"),
            Map.entry("connector.call", "ingest/input"),
            Map.entry("drive.read", "ingest/input"),
            Map.entry("load_template", "ingest/input"));

    /** A descriptor the vendor has retired, so a text row naming it is refused unless it is being switched off. */
    @TestConfiguration
    static class DeprecatedDescriptor {

        @Bean
        @Primary
        ModelDescriptors descriptorsWithADeprecatedOne(SeededModelDescriptors seeded) {
            ModelDescriptor retired = new ModelDescriptor(DEPRECATED_ID, "gemini", "flash",
                    List.of(Modality.TEXT), new Limits(1000, 100), true, StructuredOutput.NONE,
                    new Thinking(Knob.NONE, List.of(), null), false, Status.DEPRECATED, null, "gemini-3.8-flash",
                    null);
            return new ModelDescriptors() {
                @Override
                public Optional<ModelDescriptor> describe(String id) {
                    return DEPRECATED_ID.equals(id) ? Optional.of(retired) : seeded.describe(id);
                }

                @Override
                public List<ModelDescriptor> all() {
                    List<ModelDescriptor> all = new ArrayList<>(seeded.all());
                    all.add(retired);
                    return all;
                }
            };
        }
    }

    @Autowired
    private UserService userService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyModelRows() {
        jdbc.update("DELETE FROM model_catalog");
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

    private static String modelPath(String capability, String modelKey) {
        return CAPABILITIES + "/" + capability + "/model?key=" + modelKey;
    }

    private static Map<String, Object> row(String modelKey, String mode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelKey", modelKey);
        body.put("providerKey", "fal");
        body.put("mode", mode);
        return body;
    }

    private static String uniqueModel() {
        return "fal-ai/it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> storedRow(String capability, String modelKey) {
        return jdbc.queryForMap("SELECT provider_key, vendor_label, mode, enabled, not_implemented"
                + " FROM model_catalog WHERE capability = ? AND model_key = ?", capability, modelKey);
    }

    private void insertRow(String capability, String modelKey, String providerKey, String mode) {
        jdbc.update("INSERT INTO model_catalog (capability, model_key, provider_key, mode) VALUES (?, ?, ?, ?)",
                capability, modelKey, providerKey, mode);
    }

    @Test
    void theSeededRowsAreTheTwentyThreeStepTypesWithTheirCategoryAndGroup() {
        Map<String, String> stored = new LinkedHashMap<>();
        jdbc.queryForList("SELECT capability, category, ui_group, ui_icon FROM capability_catalog").forEach(row -> {
            stored.put((String) row.get("capability"), row.get("category") + "/" + row.get("ui_group"));
            assertThat(row.get("ui_icon")).isNull();
        });

        assertThat(stored).hasSize(23).containsExactlyInAnyOrderEntriesOf(SEEDED);
    }

    @Test
    void theDatabaseRefusesACategoryAGroupAndAModeOutsideTheirSets() {
        assertThatThrownBy(() -> jdbc.update("UPDATE capability_catalog SET category = 'bogus' WHERE capability = 'echo'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE capability_catalog SET ui_group = 'bogus' WHERE capability = 'echo'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow("tts", "fal-ai/voice", "fal", "bogus"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRefusesAModelKeyWithAnEmptySegment() {
        assertThatThrownBy(() -> insertRow("tts", "fal-ai//voice", "fal", "audio"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow("tts", "/voice", "fal", "audio"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSlashedModelKeyIsStoredAndReadThroughTheKeyParameter() {
        String token = adminToken("slashed");

        ResponseEntity<Map> created = call(HttpMethod.POST, CAPABILITIES + "/tts/models", token,
                row("fal-ai/minimax/voice-clone", "audio"), Map.class);
        ResponseEntity<Map> read = call(HttpMethod.GET, modelPath("tts", "fal-ai/minimax/voice-clone"), token, null,
                Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).containsEntry("capability", "tts").containsEntry("modelKey",
                "fal-ai/minimax/voice-clone");
        assertThat(storedRow("tts", "fal-ai/minimax/voice-clone")).containsEntry("mode", "audio");
    }

    @Test
    void aModelOfAMissingCapabilityAndTheDeleteOfACapabilityWithModelsAreRefused() {
        assertThatThrownBy(() -> insertRow("no-such-capability", "fal-ai/voice", "fal", "audio"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO capability_catalog (capability, category, ui_group) VALUES ('it-temp', 'control',"
                + " 'control')");
        try {
            insertRow("it-temp", "fal-ai/voice", "fal", "audio");

            assertThatThrownBy(() -> jdbc.update("DELETE FROM capability_catalog WHERE capability = 'it-temp'"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM model_catalog WHERE capability = 'it-temp'");
            jdbc.update("DELETE FROM capability_catalog WHERE capability = 'it-temp'");
        }
    }

    @Test
    void oneModelKeyMayLiveUnderTwoCapabilities() {
        insertRow("tts", "fal-ai/shared", "fal", "audio");
        insertRow("generate_speech", "fal-ai/shared", "fal", "audio");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_catalog WHERE model_key = 'fal-ai/shared'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void anAdminCreatesAVideoRowWithDefaults() {
        String token = adminToken("create");
        String key = uniqueModel();

        ResponseEntity<Map> created = call(HttpMethod.POST, CAPABILITIES + "/generate_video/models", token,
                row(key, "video"), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("modelKey", key).containsEntry("enabled", true)
                .containsEntry("notImplemented", false).containsEntry("providerKey", "fal");
        assertThat(created.getBody().get("descriptor")).isNull();
        assertThat(storedRow("generate_video", key)).containsEntry("enabled", true);
    }

    @Test
    void aDuplicateRowIsConflictAndKeepsTheStoredRow() {
        String token = adminToken("duplicate");
        String key = uniqueModel();
        Map<String, Object> first = row(key, "video");
        first.put("vendorLabel", "First");
        call(HttpMethod.POST, CAPABILITIES + "/generate_video/models", token, first, Map.class);
        Map<String, Object> second = row(key, "video");
        second.put("vendorLabel", "Second");

        ResponseEntity<Map> response = call(HttpMethod.POST, CAPABILITIES + "/generate_video/models", token, second,
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "catalog-model-exists");
        assertThat(storedRow("generate_video", key)).containsEntry("vendor_label", "First");
    }

    @Test
    void twoCreatesOfOneRowAtOnceGiveOneWinnerAndTheRowHoldsTheWinnersFields() throws Exception {
        String token = adminToken("race");
        String key = uniqueModel();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<ResponseEntity<Map>>> calls = new ArrayList<>();
        for (String label : List.of("Winner A", "Winner B")) {
            Map<String, Object> body = row(key, "video");
            body.put("vendorLabel", label);
            calls.add(() -> {
                start.await();
                return call(HttpMethod.POST, CAPABILITIES + "/generate_video/models", token, body, Map.class);
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
            assertThat(jdbc.queryForObject("SELECT count(*) FROM model_catalog WHERE model_key = ?", Integer.class,
                    key)).isEqualTo(1);
            assertThat(storedRow("generate_video", key)).containsEntry("vendor_label",
                    bodies.get(HttpStatus.CREATED).get("vendorLabel"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replacingWithEnabledOffReadsBackDisabled() {
        String token = adminToken("replace");
        String key = uniqueModel();
        call(HttpMethod.POST, CAPABILITIES + "/generate_video/models", token, row(key, "video"), Map.class);
        Map<String, Object> body = row(key, "video");
        body.remove("modelKey");
        body.put("enabled", false);

        ResponseEntity<Map> replaced = call(HttpMethod.PUT, modelPath("generate_video", key), token, body, Map.class);
        ResponseEntity<Map> read = call(HttpMethod.GET, modelPath("generate_video", key), token, null, Map.class);

        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).containsEntry("enabled", false);
    }

    @Test
    void aTextRowWithADeprecatedDescriptorCanBeSwitchedOffButNotBackOn() {
        String token = adminToken("deprecated");
        insertRow("generate_script", DEPRECATED_ID, "gemini", "text");
        Map<String, Object> off = row(DEPRECATED_ID, "text");
        off.remove("modelKey");
        off.put("providerKey", "gemini");
        off.put("enabled", false);
        Map<String, Object> on = new LinkedHashMap<>(off);
        on.put("enabled", true);

        ResponseEntity<Map> disabled = call(HttpMethod.PUT, modelPath("generate_script", DEPRECATED_ID), token, off,
                Map.class);
        ResponseEntity<Map> enabled = call(HttpMethod.PUT, modelPath("generate_script", DEPRECATED_ID), token, on,
                Map.class);

        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getBody()).containsEntry("enabled", false);
        assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(enabled.getBody()).containsEntry("code", "catalog-model-invalid");
        assertThat((Map<String, Object>) enabled.getBody().get("errors")).containsKey("modelKey");
        assertThat(storedRow("generate_script", DEPRECATED_ID)).containsEntry("enabled", false);
    }

    @Test
    void anUndescribedTextModelIsBadRequestOnModelKey() {
        String token = adminToken("undescribed");
        Map<String, Object> body = row("gemini-9-flash", "text");
        body.put("providerKey", "gemini");

        ResponseEntity<Map> response = call(HttpMethod.POST, CAPABILITIES + "/generate_script/models", token, body,
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "catalog-model-invalid");
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("modelKey");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_catalog", Integer.class)).isZero();
    }

    @Test
    void aDescribedTextRowIsCreatedAndReadBackWithItsDescriptor() {
        String token = adminToken("described");
        Map<String, Object> body = row("gemini-3.8-flash", "text");
        body.put("providerKey", "gemini");

        ResponseEntity<Map> created = call(HttpMethod.POST, CAPABILITIES + "/generate_script/models", token, body,
                Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Map<String, Object>) created.getBody().get("descriptor")).containsEntry("id", "gemini-3.8-flash");
    }

    @Test
    void deletingARowAnswersNoContentAndThenNotFound() {
        String token = adminToken("delete");
        String key = uniqueModel();
        call(HttpMethod.POST, CAPABILITIES + "/generate_video/models", token, row(key, "video"), Map.class);

        ResponseEntity<Void> deleted = call(HttpMethod.DELETE, modelPath("generate_video", key), token, null,
                Void.class);
        ResponseEntity<Map> again = call(HttpMethod.DELETE, modelPath("generate_video", key), token, null,
                Map.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(again.getBody()).containsEntry("code", "catalog-model-not-found");
    }

    @Test
    void aPageSizeAboveTheCapIsBadRequest() {
        String token = adminToken("page-size");

        ResponseEntity<Map> response = call(HttpMethod.GET, CAPABILITIES + "/generate_video/models?size=51", token,
                null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theListPagesTheRowsOfOneCapabilityInKeyOrder() {
        String token = adminToken("list");
        for (String key : List.of("fal-ai/b", "fal-ai/a", "fal-ai/c")) {
            insertRow("generate_video", key, "fal", "video");
        }

        ResponseEntity<Map> page = call(HttpMethod.GET, CAPABILITIES + "/generate_video/models?size=2", token, null,
                Map.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).containsEntry("total", 3).containsEntry("size", 2);
        assertThat((List<Map<String, Object>>) page.getBody().get("items")).extracting(item -> item.get("modelKey"))
                .containsExactly("fal-ai/a", "fal-ai/b");
    }

    @Test
    void anUnknownCapabilityIsNotFound() {
        String token = adminToken("unknown-capability");

        ResponseEntity<Map> list = call(HttpMethod.GET, CAPABILITIES + "/nope/models", token, null, Map.class);
        ResponseEntity<Map> one = call(HttpMethod.GET, modelPath("nope", "fal-ai/voice"), token, null, Map.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(list.getBody()).containsEntry("code", "capability-not-found");
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(one.getBody()).containsEntry("code", "capability-not-found");
    }

    @Test
    void replacingTheDisplayFieldsOfACapabilityMovesUpdatedAt() {
        String token = adminToken("replace-capability");
        Instant before = jdbc.queryForObject("SELECT updated_at FROM capability_catalog WHERE capability = 'probe'",
                java.sql.Timestamp.class).toInstant();
        try {
            ResponseEntity<Map> replaced = call(HttpMethod.PUT, CAPABILITIES + "/probe", token,
                    Map.of("category", "analyze", "uiGroup", "analyze", "uiIcon", "scan-search"), Map.class);

            assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replaced.getBody()).containsEntry("uiIcon", "scan-search");
            Instant after = jdbc.queryForObject("SELECT updated_at FROM capability_catalog WHERE capability = 'probe'",
                    java.sql.Timestamp.class).toInstant();
            assertThat(after).isAfter(before);
        } finally {
            jdbc.update("UPDATE capability_catalog SET ui_icon = NULL, updated_at = ? WHERE capability = 'probe'",
                    java.sql.Timestamp.from(before));
        }
    }

    @Test
    void anIconWithAForbiddenCharacterIsBadRequestOnUiIcon() {
        String token = adminToken("bad-icon");

        ResponseEntity<Map> response = call(HttpMethod.PUT, CAPABILITIES + "/probe", token,
                Map.of("category", "analyze", "uiGroup", "analyze", "uiIcon", "Languages!"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("uiIcon");
    }

    @Test
    void aModelKeyWithAnEmptySegmentIsBadRequestOnModelKey() {
        String token = adminToken("bad-key");

        ResponseEntity<Map> response = call(HttpMethod.POST, CAPABILITIES + "/tts/models", token,
                row("fal-ai//voice", "audio"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("modelKey");
    }

    @Test
    void aRoleWithNeitherKeyIsRefusedTheList() {
        String token = staffToken("support-list", "CUSTOMER_SUPPORT");

        ResponseEntity<String> response = call(HttpMethod.GET, CAPABILITIES, token, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aViewOnlyRoleReadsButCannotCreate() {
        String token = staffToken("support-view-only", "CUSTOMER_SUPPORT");
        jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES ('CUSTOMER_SUPPORT', ?)"
                + " ON CONFLICT DO NOTHING", Permission.AI_MODEL_VIEW.key());
        try {
            ResponseEntity<String> read = call(HttpMethod.GET, CAPABILITIES, token, null, String.class);
            ResponseEntity<String> write = call(HttpMethod.POST, CAPABILITIES + "/generate_video/models", token,
                    row(uniqueModel(), "video"), String.class);

            assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM model_catalog", Integer.class)).isZero();
        } finally {
            jdbc.update("DELETE FROM role_permission WHERE role_code = 'CUSTOMER_SUPPORT' AND permission_key = ?",
                    Permission.AI_MODEL_VIEW.key());
        }
    }
}
