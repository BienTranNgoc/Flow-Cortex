package com.flowcortex.app.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** The onboarding sample: the admin routes, the eligibility verdicts and what a member's status serves, switch on. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = "flowcortex.onboarding.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class OnboardingSampleIT extends AbstractPostgresBootTest {

    private static final String SAMPLE = "/api/v1/admin/onboarding/sample";
    private static final String STATUS = "/api/v1/onboarding/status";
    private static final String VI_INPUT = "Viết mô tả cho chiếc áo thun";
    private static final String EN_INPUT = "Write a description for a t-shirt";

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    private record Account(UUID id, String token) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptySample() {
        jdbc.update("UPDATE onboarding_sample SET workflow_id = NULL, sample_input = NULL, source_locale = NULL "
                + "WHERE id = 1");
        jdbc.update("DELETE FROM onboarding_sample_translation");
    }

    @Test
    void adminSetsAnEligibleSample() {
        Account admin = admin();
        UUID workflow = sharedWorkflow(admin, "Product caption");

        ResponseEntity<Map> saved = put(admin, SAMPLE, sampleBody(workflow, VI_INPUT, "vi"), null);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> view = saved.getBody();
        assertThat((Map<String, Object>) view.get("workflow")).containsEntry("name", "Product caption");
        assertThat(view).containsEntry("shown", true).containsEntry("reason", null)
                .containsEntry("sampleInput", VI_INPUT).containsEntry("sourceLocale", "vi");
        assertThat((String) view.get("sourceHash")).hasSize(64);
        assertThat((Map<String, Object>) view.get("translations")).containsOnlyKeys("en").containsEntry("en", null);
        assertThat(view.get("updatedBy")).isNotNull();
        assertThat(get(admin, SAMPLE, null).getBody()).isEqualTo(view);
    }

    @Test
    void ineligibleSampleIs422AndKeepsTheStoredOne() {
        Account admin = admin();
        UUID good = sharedWorkflow(admin, "Good");
        UUID privateOne = privateWorkflow(admin);
        put(admin, SAMPLE, sampleBody(good, VI_INPUT, "vi"), null);

        ResponseEntity<Map> refused = put(admin, SAMPLE, sampleBody(privateOne, "other", "vi"), "en");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody()).containsEntry("code", "ONBOARDING_SAMPLE_INELIGIBLE");
        assertThat((Map<String, Object>) refused.getBody().get("errors")).containsEntry("workflowId",
                "This workflow is private; share it with every member first");
        Map<String, Object> stored = get(admin, SAMPLE, null).getBody();
        assertThat(stored).containsEntry("sampleInput", VI_INPUT);
        assertThat((Map<String, Object>) stored.get("workflow")).containsEntry("name", "Good");
    }

    @Test
    void ineligibleReasonIsTranslatedForAVietnameseAdmin() {
        Account admin = admin();
        UUID draft = jdbcDraft(admin);

        ResponseEntity<Map> refused = put(admin, SAMPLE, sampleBody(draft, VI_INPUT, "vi"), "vi");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((Map<String, Object>) refused.getBody().get("errors")).containsEntry("workflowId",
                "Quy trình này chưa được xuất bản");
    }

    @Test
    void blankSampleInputIs400() {
        Account admin = admin();
        UUID workflow = sharedWorkflow(admin, "Caption");

        ResponseEntity<Map> refused = put(admin, SAMPLE, sampleBody(workflow, "   ", "vi"), "en");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) refused.getBody().get("errors"))
                .containsEntry("sampleInput", "Enter a sample input for this workflow");
    }

    @Test
    void missingSourceLocaleIs400() {
        Account admin = admin();
        UUID workflow = sharedWorkflow(admin, "Caption");

        ResponseEntity<Map> refused = put(admin, SAMPLE, "{\"workflowId\":\"" + workflow + "\",\"sampleInput\":\"x\"}",
                null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) refused.getBody().get("errors")).containsOnlyKeys("sourceLocale");
    }

    @Test
    void nonUuidWorkflowIdIs400() {
        Account admin = admin();

        ResponseEntity<Map> refused = put(admin, SAMPLE, "{\"workflowId\":\"nope\",\"sampleInput\":\"x\","
                + "\"sourceLocale\":\"vi\"}", null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) refused.getBody().get("errors")).containsOnlyKeys("workflowId");
    }

    @Test
    void lineBreakInSampleInputIs400() {
        Account admin = admin();
        UUID workflow = sharedWorkflow(admin, "Caption");

        ResponseEntity<Map> refused = put(admin, SAMPLE, sampleBody(workflow, "one\\ntwo", "vi"), "en");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) refused.getBody().get("errors"))
                .containsEntry("sampleInput", "The sample input must be a single line");
    }

    @Test
    void memberGets403OnTheAdminRoutes() {
        Account admin = admin();
        Account member = member(admin);
        String hash = "a".repeat(64);

        assertThat(get(member, SAMPLE, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(member, SAMPLE, "{\"workflowId\":null}", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(member, SAMPLE + "/translations/en", "{\"sourceHash\":\"" + hash + "\",\"input\":\"x\"}", null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void translationOfAnUnsetSampleIs404() {
        Account admin = admin();

        ResponseEntity<Map> refused = put(admin, SAMPLE + "/translations/en",
                "{\"sourceHash\":\"" + "a".repeat(64) + "\",\"input\":\"x\"}", null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sourceLocaleIsNotEditableAs422() {
        Account admin = admin();
        UUID workflow = sharedWorkflow(admin, "Caption");
        String hash = (String) put(admin, SAMPLE, sampleBody(workflow, VI_INPUT, "vi"), null).getBody()
                .get("sourceHash");

        ResponseEntity<Map> refused = put(admin, SAMPLE + "/translations/vi",
                "{\"sourceHash\":\"" + hash + "\",\"input\":\"x\"}", "en");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((Map<String, Object>) refused.getBody().get("errors")).containsKey("locale");
    }

    @Test
    void unknownTranslationLocaleIs422() {
        Account admin = admin();
        UUID workflow = sharedWorkflow(admin, "Caption");
        String hash = (String) put(admin, SAMPLE, sampleBody(workflow, VI_INPUT, "vi"), null).getBody()
                .get("sourceHash");

        ResponseEntity<Map> refused = put(admin, SAMPLE + "/translations/xx",
                "{\"sourceHash\":\"" + hash + "\",\"input\":\"x\"}", "en");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((Map<String, Object>) refused.getBody().get("errors")).containsOnlyKeys("locale");
    }

    @Test
    void lineBreakInTranslationIs400KeyedInput() {
        Account admin = admin();
        UUID workflow = sharedWorkflow(admin, "Caption");
        String hash = (String) put(admin, SAMPLE, sampleBody(workflow, VI_INPUT, "vi"), null).getBody()
                .get("sourceHash");

        ResponseEntity<Map> refused = put(admin, SAMPLE + "/translations/en",
                "{\"sourceHash\":\"" + hash + "\",\"input\":\"one\\ntwo\"}", "en");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) refused.getBody().get("errors"))
                .containsEntry("input", "The translation must be a single line").hasSize(1);
    }

    @Test
    void oversizedTranslationIs400KeyedInput() {
        Account admin = admin();
        UUID workflow = sharedWorkflow(admin, "Caption");
        String hash = (String) put(admin, SAMPLE, sampleBody(workflow, VI_INPUT, "vi"), null).getBody()
                .get("sourceHash");

        ResponseEntity<Map> refused = put(admin, SAMPLE + "/translations/en",
                "{\"sourceHash\":\"" + hash + "\",\"input\":\"" + "a".repeat(10_001) + "\"}", "en");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) refused.getBody().get("errors")).containsOnlyKeys("input");
    }

    @Test
    void everyPutLeavesOneAuditLineWhateverTheOutcome(CapturedOutput output) {
        Account admin = admin();
        UUID workflow = sharedWorkflow(admin, "Caption");
        UUID draft = jdbcDraft(admin);
        String viHash = (String) put(admin, SAMPLE, sampleBody(workflow, VI_INPUT, "vi"), null).getBody()
                .get("sourceHash");
        put(admin, SAMPLE, sampleBody(draft, VI_INPUT, "vi"), null);
        put(admin, SAMPLE, sampleBody(workflow, "   ", "vi"), null);
        put(admin, SAMPLE, "{\"workflowId\":", null);
        put(admin, SAMPLE + "/translations/en", "{\"sourceHash\":\"" + viHash + "\",\"input\":\"x\"}", null);
        put(admin, SAMPLE + "/translations/en", "{\"sourceHash\":\"" + viHash + "\",\"input\":\"\"}", null);
        put(admin, SAMPLE + "/translations/en", "{\"sourceHash\":\"" + "b".repeat(64) + "\",\"input\":\"x\"}", null);
        put(admin, SAMPLE + "/translations/vi", "{\"sourceHash\":\"" + viHash + "\",\"input\":\"x\"}", null);
        put(admin, SAMPLE + "/translations/en", "{\"sourceHash\":\"" + viHash + "\",\"input\":\"a\\nb\"}", null);
        put(admin, SAMPLE + "/translations/xx", "{\"sourceHash\":\"" + viHash + "\",\"input\":\"x\"}", null);
        put(admin, SAMPLE, "{\"workflowId\":null}", null);
        put(admin, SAMPLE + "/translations/en", "{\"sourceHash\":\"" + viHash + "\",\"input\":\"x\"}", null);

        String who = " adminId=" + admin.id();
        assertThat(auditLines(output)).containsExactly(
                "onboarding.sample outcome=saved" + who + " workflowId=" + workflow + " reason=null",
                "onboarding.sample outcome=refused" + who + " workflowId=" + draft + " reason=unpublished",
                "onboarding.sample outcome=refused" + who + " workflowId=" + workflow + " reason=invalid:sampleInput",
                "onboarding.sample outcome=refused" + who + " workflowId=null reason=unreadable",
                "onboarding.sample.translation outcome=saved" + who + " locale=en reason=null",
                "onboarding.sample.translation outcome=cleared" + who + " locale=en reason=null",
                "onboarding.sample.translation outcome=refused" + who + " locale=en reason=changed",
                "onboarding.sample.translation outcome=refused" + who + " locale=vi reason=locale_not_editable",
                "onboarding.sample.translation outcome=refused" + who + " locale=en reason=invalid:input",
                "onboarding.sample.translation outcome=refused" + who + " locale=null reason=locale_not_editable",
                "onboarding.sample outcome=cleared" + who + " workflowId=null reason=null",
                "onboarding.sample.translation outcome=refused" + who + " locale=en reason=not_set");
    }

    @Test
    void staleTranslationIs409AndStoresNothing() {
        Account admin = admin();
        UUID workflow = sharedWorkflow(admin, "Caption");
        String oldHash = (String) put(admin, SAMPLE, sampleBody(workflow, VI_INPUT, "vi"), null).getBody()
                .get("sourceHash");
        put(admin, SAMPLE, sampleBody(workflow, "Nội dung mới", "vi"), null);

        ResponseEntity<Map> stale = put(admin, SAMPLE + "/translations/en",
                "{\"sourceHash\":\"" + oldHash + "\",\"input\":\"" + EN_INPUT + "\"}", null);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_sample_translation", Long.class)).isZero();
    }

    @Test
    void translationIsServedToAnEnglishMemberAndRemovedWhenBlank() {
        Account admin = admin();
        Account member = member(admin);
        UUID workflow = sharedWorkflow(admin, "Caption");
        String hash = (String) put(admin, SAMPLE, sampleBody(workflow, VI_INPUT, "vi"), null).getBody()
                .get("sourceHash");

        ResponseEntity<Map> saved = put(admin, SAMPLE + "/translations/en",
                "{\"sourceHash\":\"" + hash + "\",\"input\":\"" + EN_INPUT + "\"}", null);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) saved.getBody().get("translations")).containsEntry("en", EN_INPUT);
        assertThat(sampleOf(member, "en")).containsEntry("input", EN_INPUT).containsEntry("name", "Caption");
        assertThat(sampleOf(member, "vi")).containsEntry("input", VI_INPUT);

        put(admin, SAMPLE + "/translations/en", "{\"sourceHash\":\"" + hash + "\",\"input\":\"  \"}", null);

        assertThat(sampleOf(member, "en")).containsEntry("input", VI_INPUT);
    }

    @Test
    void missingTranslationFallsBackToTheSource() {
        Account admin = admin();
        Account member = member(admin);
        put(admin, SAMPLE, sampleBody(sharedWorkflow(admin, "Caption"), VI_INPUT, "vi"), null);

        assertThat(sampleOf(member, "en")).containsEntry("input", VI_INPUT);
    }

    @Test
    void changedInputHidesTheOldTranslation() {
        Account admin = admin();
        Account member = member(admin);
        UUID workflow = sharedWorkflow(admin, "Caption");
        String hash = (String) put(admin, SAMPLE, sampleBody(workflow, VI_INPUT, "vi"), null).getBody()
                .get("sourceHash");
        put(admin, SAMPLE + "/translations/en", "{\"sourceHash\":\"" + hash + "\",\"input\":\"" + EN_INPUT + "\"}",
                null);

        put(admin, SAMPLE, sampleBody(workflow, "Nội dung mới", "vi"), null);

        assertThat(sampleOf(member, "en")).containsEntry("input", "Nội dung mới");
    }

    @Test
    void deletedSampleIsNotServed() {
        Account admin = admin();
        Account member = member(admin);
        UUID workflow = sharedWorkflow(admin, "Caption");
        put(admin, SAMPLE, sampleBody(workflow, VI_INPUT, "vi"), null);
        assertThat(sampleOf(member, "en")).isNotNull();

        workflowService.deleteWorkflow(admin.id(), workflow);

        assertThat(status(member, "en")).containsEntry("sampleWorkflow", null);
        Map<String, Object> view = get(admin, SAMPLE, null).getBody();
        assertThat(view).containsEntry("shown", false).containsEntry("reason", "deleted");
    }

    @Test
    void clearRemovesTheSampleFromTheStatus() {
        Account admin = admin();
        Account member = member(admin);
        put(admin, SAMPLE, sampleBody(sharedWorkflow(admin, "Caption"), VI_INPUT, "vi"), null);

        ResponseEntity<Map> cleared = put(admin, SAMPLE, "{\"workflowId\":null}", null);

        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody()).containsEntry("workflow", null).containsEntry("shown", false)
                .containsEntry("reason", null).containsEntry("sampleInput", null);
        assertThat((Map<String, Object>) cleared.getBody().get("translations")).isEmpty();
        assertThat(status(member, "en")).containsEntry("sampleWorkflow", null);
    }

    private Map<String, Object> sampleOf(Account member, String language) {
        return (Map<String, Object>) status(member, language).get("sampleWorkflow");
    }

    private Map<String, Object> status(Account member, String language) {
        ResponseEntity<Map> response = get(member, STATUS, language);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static List<String> auditLines(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains(": onboarding.sample"))
                .map(line -> line.substring(line.indexOf(": onboarding.sample") + 2)).toList();
    }

    private static String sampleBody(UUID workflowId, String input, String locale) {
        return "{\"workflowId\":\"" + workflowId + "\",\"sampleInput\":\"" + input + "\",\"sourceLocale\":\""
                + locale + "\"}";
    }

    private UUID sharedWorkflow(Account owner, String name) {
        return workflowService.createWorkflow(owner.id(), name, true,
                List.of(new NewWorkflowStep("Echo", "echo", null))).id();
    }

    private UUID privateWorkflow(Account owner) {
        return workflowService.createWorkflow(owner.id(), "private-" + UUID.randomUUID(), false,
                List.of(new NewWorkflowStep("Echo", "echo", null))).id();
    }

    private UUID jdbcDraft(Account owner) {
        return workflowService.createDraft(owner.id(), "draft-" + UUID.randomUUID(), true).id();
    }

    private Account admin() {
        UserView user = userService.createInitialAdmin("admin-" + UUID.randomUUID() + "@example.com", "password123",
                "admin");
        return login(user);
    }

    private Account member(Account admin) {
        UserView user = userService.createUser(admin.id(), "member-" + UUID.randomUUID() + "@example.com",
                "password123", "member", "MEMBER");
        return login(user);
    }

    private Account login(UserView user) {
        ResponseEntity<TokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(user.email(), "password123"), TokenResponse.class);
        return new Account(user.id(), login.getBody().accessToken());
    }

    private ResponseEntity<Map> get(Account who, String path, String language) {
        return call(HttpMethod.GET, who, path, null, language);
    }

    private ResponseEntity<Map> put(Account who, String path, String body, String language) {
        return call(HttpMethod.PUT, who, path, body, language);
    }

    private ResponseEntity<Map> call(HttpMethod method, Account who, String path, String body, String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(who.token());
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }
}
