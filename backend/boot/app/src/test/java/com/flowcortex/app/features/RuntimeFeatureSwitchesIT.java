package com.flowcortex.app.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

/**
 * An admin stores a value through the settings API and the gated route and {@code /features} follow
 * on the next request, with the server configuration on for every feature.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.auth.password-reset.enabled=true",
        "flowcortex.workflow.cancel.enabled=true",
        "flowcortex.support.enabled=true",
        "flowcortex.support.assistant.enabled=true"})
@AutoConfigureTestRestTemplate
class RuntimeFeatureSwitchesIT extends AbstractPostgresBootTest {

    private static final String PASSWORD = "password123";
    private static final String PASSWORD_RESET_KEY = "user.pw_reset.enabled";
    private static final String CANCELLATION_KEY = "workflow.run_cancellation.enabled";
    private static final String HELP_CENTER_KEY = "support.help_center.enabled";
    private static final String ASSISTANT_KEY = "support.assistant.enabled";
    private static final List<String> KEYS =
            List.of(PASSWORD_RESET_KEY, CANCELLATION_KEY, HELP_CENTER_KEY, ASSISTANT_KEY);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    private UserView admin;
    private String adminToken;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record ForgotRequest(String email) {
    }

    record AdminFeature(String name, String settingKey, boolean value) {
    }

    record AdminFeatureList(List<AdminFeature> items) {
    }

    @BeforeEach
    void adminSignsIn() {
        admin = userService.createInitialAdmin("flags-admin-" + UUID.randomUUID() + "@example.com", PASSWORD,
                "Admin");
        adminToken = restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(admin.email(), PASSWORD),
                TokenResponse.class).getBody().accessToken();
    }

    @AfterEach
    void removeStoredValues() {
        for (String key : KEYS) {
            jdbc.update("DELETE FROM app_setting WHERE key = ?", key);
            jdbc.update("DELETE FROM app_setting_change WHERE key = ?", key);
        }
    }

    static Stream<Arguments> gatedRoutes() {
        return Stream.of(
                Arguments.of(PASSWORD_RESET_KEY, "passwordReset", HttpMethod.POST, "/api/v1/auth/forgot-password",
                        "This feature is turned off", HttpStatus.ACCEPTED),
                Arguments.of(CANCELLATION_KEY, "runCancellation", HttpMethod.POST,
                        "/api/v1/runs/" + UUID.randomUUID() + "/cancel", "This feature is turned off",
                        HttpStatus.NOT_FOUND),
                Arguments.of(HELP_CENTER_KEY, "supportWidget", HttpMethod.GET, "/api/v1/support/kb/groups",
                        "support-disabled", HttpStatus.OK));
    }

    @ParameterizedTest
    @MethodSource("gatedRoutes")
    void aStoredOffTurnsTheFeatureOffOnTheNextRequestAndAResetTurnsItBackOn(String key, String field,
            HttpMethod method, String path, String refusal, HttpStatus backOn) {
        assertThat(features().get(field)).isEqualTo(true);

        assertThat(store(key, false).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> off = call(method, path);
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(off.getBody()).contains(refusal);
        assertThat(features().get(field)).isEqualTo(false);

        assertThat(reset(key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(method, path).getStatusCode()).isEqualTo(backOn);
        assertThat(features().get(field)).isEqualTo(true);
    }

    @Test
    void theAssistantKeyDrivesSupportAssistant() {
        assertThat(store(ASSISTANT_KEY, false).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> features = features();

        assertThat(features.get("supportAssistant")).isEqualTo(false);
        assertThat(features.get("supportWidget")).isEqualTo(true);
    }

    @Test
    void anUnparseableStoredValueReadsOff() {
        jdbc.update("INSERT INTO app_setting (key, value, updated_by, updated_at) VALUES (?, 'maybe', NULL, now())",
                HELP_CENTER_KEY);

        ResponseEntity<String> help = call(HttpMethod.GET, "/api/v1/support/kb/groups");

        assertThat(help.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(help.getBody()).contains("support-disabled");
        assertThat(features().get("supportWidget")).isEqualTo(false);
    }

    @Test
    void theAdminListHasOneItemPerFieldInBodyOrderNamingTheRuntimeKeys() {
        assertThat(store(HELP_CENTER_KEY, false).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<AdminFeatureList> response = restTemplate.exchange("/api/v1/admin/features", HttpMethod.GET,
                new HttpEntity<>(bearer()), AdminFeatureList.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<AdminFeature> items = response.getBody().items();
        assertThat(items).extracting(AdminFeature::name).containsExactly("emailConfirmation", "phoneVerification",
                "uploadContentCheck", "runCancellation", "credits", "passwordReset", "supportWidget",
                "supportAssistant", "personalAccessTokens", "onboarding", "chat");
        assertThat(items).filteredOn(item -> item.settingKey() != null)
                .extracting(AdminFeature::name, AdminFeature::settingKey, AdminFeature::value)
                .containsExactly(
                        tuple("runCancellation", CANCELLATION_KEY, true),
                        tuple("passwordReset", PASSWORD_RESET_KEY, true),
                        tuple("supportWidget", HELP_CENTER_KEY, false),
                        tuple("supportAssistant", ASSISTANT_KEY, true));
    }

    @Test
    void aCallerWithoutTheViewKeyGets403() {
        UserView member = userService.createUser(admin.id(), "flags-member-" + UUID.randomUUID() + "@example.com",
                PASSWORD, "Member", "MEMBER");
        String memberToken = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(member.email(), PASSWORD), TokenResponse.class).getBody().accessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(memberToken);

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/features", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<String> store(String key, boolean value) {
        return restTemplate.exchange("/api/v1/admin/settings/" + key, HttpMethod.PUT,
                new HttpEntity<>(Map.of("value", value), bearer()), String.class);
    }

    private ResponseEntity<String> reset(String key) {
        return restTemplate.exchange("/api/v1/admin/settings/" + key, HttpMethod.DELETE,
                new HttpEntity<>(bearer()), String.class);
    }

    private ResponseEntity<String> call(HttpMethod method, String path) {
        Object body = path.endsWith("/forgot-password") ? new ForgotRequest("nobody-" + UUID.randomUUID()
                + "@example.com") : null;
        return restTemplate.exchange(path, method, new HttpEntity<>(body, bearer()), String.class);
    }

    private Map<?, ?> features() {
        return restTemplate.getForObject("/api/v1/features", Map.class);
    }

    private HttpHeaders bearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        return headers;
    }
}
