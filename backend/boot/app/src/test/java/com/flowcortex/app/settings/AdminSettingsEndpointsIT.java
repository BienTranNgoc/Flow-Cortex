package com.flowcortex.app.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The admin settings API over HTTP against Postgres. The keys are declared here under their own
 * group so the cases never depend on what the feature modules declare.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminSettingsEndpointsIT extends AbstractPostgresBootTest {

    private static final String BASE = "/api/v1/admin/settings";
    private static final String PASSWORD = "password123";
    private static final String SWITCH_OFF = "itapi.admin.a_switch";
    private static final String SWITCH_VETOED = "itapi.admin.b_vetoed";
    private static final String LIMIT = "itapi.admin.c_limit";
    private static final String PAGE_LIMIT = "itapi.admin.d_paged";
    private static final String UNSET_LIMIT = "itapi.admin.e_other";
    private static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");

    @TestConfiguration
    static class Keys {

        @Bean
        SettingDefinition<Boolean> itApiSwitch() {
            return SettingDefinition.switchKey(SWITCH_OFF, false, () -> null);
        }

        @Bean
        SettingDefinition<Boolean> itApiVetoed() {
            return SettingDefinition.switchKey(SWITCH_VETOED, true, () -> false);
        }

        @Bean
        SettingDefinition<Integer> itApiLimit() {
            return SettingDefinition.integerKey(LIMIT, 1, 500, () -> 30);
        }

        @Bean
        SettingDefinition<Integer> itApiPaged() {
            return SettingDefinition.integerKey(PAGE_LIMIT, 1, 1000, () -> 10);
        }

        @Bean
        SettingDefinition<Integer> itApiOther() {
            return SettingDefinition.integerKey(UNSET_LIMIT, 1, Integer.MAX_VALUE, () -> 5);
        }
    }

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record Setting(String key, String group, String type, Object value, Object decidedValue, String source,
            Object storedValue, Object configuredValue, Object defaultValue, Integer min, Integer max,
            String updatedBy, Instant updatedAt) {
    }

    record SettingList(List<Setting> items) {
    }

    record Change(Object previousValue, Object newValue, String changedBy, Instant changedAt) {
    }

    record ChangePage(List<Change> items, int page, int size, long total) {
    }

    record Problem(int status, URI type, String detail, String code, Map<String, String> errors) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    private UserView owner;
    private UserView operator;
    private String operatorToken;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'itapi.%'");
        jdbc.update("DELETE FROM app_setting_change WHERE key LIKE 'itapi.%'");
        owner = userService.createInitialAdmin(email("settings-owner"), PASSWORD, "Owner");
        operator = userService.createUser(owner.id(), email("settings-operator"), PASSWORD, "Operator",
                "PLATFORM_OPERATOR");
        operatorToken = login(operator.email());
    }

    @Test
    void listingWithSources() {
        ResponseEntity<SettingList> response = get(BASE, operatorToken, SettingList.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Setting> mine = ownKeys(response.getBody());
        assertThat(mine).extracting(Setting::key)
                .containsExactly(SWITCH_OFF, SWITCH_VETOED, LIMIT, PAGE_LIMIT, UNSET_LIMIT);
        assertThat(response.getBody().items()).extracting(Setting::key).isSorted();

        Setting unset = mine.get(0);
        assertThat(unset.group()).isEqualTo("itapi");
        assertThat(unset.type()).isEqualTo("SWITCH");
        assertThat(unset.value()).isEqualTo(false);
        assertThat(unset.source()).isEqualTo("DEFAULT");
        assertThat(unset.defaultValue()).isEqualTo(false);
        assertThat(unset.configuredValue()).isNull();
        assertThat(unset.storedValue()).isNull();
        assertThat(unset.updatedBy()).isNull();
        assertThat(unset.updatedAt()).isNull();

        Setting vetoed = mine.get(1);
        assertThat(vetoed.source()).isEqualTo("VETOED");
        assertThat(vetoed.value()).isEqualTo(false);
        assertThat(vetoed.configuredValue()).isEqualTo(false);

        Setting limit = mine.get(2);
        assertThat(limit.type()).isEqualTo("INTEGER");
        assertThat(limit.value()).isEqualTo(30);
        assertThat(limit.source()).isEqualTo("CONFIGURED");
        assertThat(limit.configuredValue()).isEqualTo(30);
        assertThat(limit.defaultValue()).isNull();
        assertThat(limit.min()).isEqualTo(1);
        assertThat(limit.max()).isEqualTo(500);
    }

    @Test
    void writeAnswersTheNewView() {
        ResponseEntity<Setting> response = put(LIMIT, "{\"value\":12}", operatorToken, Setting.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Setting view = response.getBody();
        assertThat(view.value()).isEqualTo(12);
        assertThat(view.decidedValue()).isEqualTo(12);
        assertThat(view.storedValue()).isEqualTo(12);
        assertThat(view.configuredValue()).isEqualTo(30);
        assertThat(view.source()).isEqualTo("STORED");
        assertThat(view.updatedBy()).isEqualTo(operator.email());
        assertThat(view.updatedAt()).isNotNull();

        Setting listed = ownKeys(get(BASE, operatorToken, SettingList.class).getBody()).get(2);
        assertThat(listed.value()).isEqualTo(12);
    }

    @Test
    void unknownKeyIs404WithoutQueryingTheTables() {
        long before = rows();

        ResponseEntity<Problem> update = put("itapi.admin.nothing", "{\"value\":1}", operatorToken, Problem.class);
        ResponseEntity<Problem> reset = exchange(HttpMethod.DELETE, BASE + "/itapi.admin.nothing", operatorToken,
                null, null, Problem.class);
        ResponseEntity<Problem> history = get(BASE + "/itapi.admin.nothing/history", operatorToken, Problem.class);

        for (ResponseEntity<Problem> response : List.of(update, reset, history)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("not-found"));
            assertThat(response.getBody().detail()).isEqualTo("Setting not found: itapi.admin.nothing");
        }
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void fractionForAnIntegerKeyIs422() {
        ResponseEntity<Problem> english = put(LIMIT, "{\"value\":12.5}", operatorToken, Problem.class, null);
        ResponseEntity<Problem> vietnamese = put(LIMIT, "{\"value\":12.5}", operatorToken, Problem.class, "vi");

        assertThat(english.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(english.getBody().code()).isEqualTo("setting.invalidValue");
        assertThat(english.getBody().errors().get("value")).isEqualTo("Must be a whole number from 1 to 500");
        assertThat(vietnamese.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(vietnamese.getBody().errors().get("value")).isEqualTo("Phải là số nguyên từ 1 đến 500");
        assertThat(rows()).isZero();
    }

    @Test
    void stringForASwitchIs422() {
        ResponseEntity<Problem> response = put(SWITCH_OFF, "{\"value\":\"false\"}", operatorToken, Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().code()).isEqualTo("setting.invalidValue");
        assertThat(response.getBody().errors().get("value")).isEqualTo("Must be on or off.");
    }

    @Test
    void belowTheMinimumIs422() {
        ResponseEntity<Problem> response = put(LIMIT, "{\"value\":0}", operatorToken, Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().errors().get("value")).isEqualTo("Must be a whole number from 1 to 500");
        assertThat(rows()).isZero();
    }

    @Test
    void aMissingValueIs400() {
        ResponseEntity<Problem> response = put(LIMIT, "{}", operatorToken, Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resetReturnsTheConfiguredValue() {
        put(LIMIT, "{\"value\":12}", operatorToken, Setting.class);

        ResponseEntity<Setting> response = exchange(HttpMethod.DELETE, BASE + "/" + LIMIT, operatorToken, null,
                null, Setting.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().value()).isEqualTo(30);
        assertThat(response.getBody().source()).isEqualTo("CONFIGURED");
        assertThat(response.getBody().storedValue()).isNull();
    }

    @Test
    void afterAResetTheRowShowsNoLastChange() {
        put(LIMIT, "{\"value\":12}", operatorToken, Setting.class);
        exchange(HttpMethod.DELETE, BASE + "/" + LIMIT, operatorToken, null, null, Setting.class);

        Setting row = ownKeys(get(BASE, operatorToken, SettingList.class).getBody()).get(2);
        ChangePage history = get(BASE + "/" + LIMIT + "/history", operatorToken, ChangePage.class).getBody();

        assertThat(row.updatedBy()).isNull();
        assertThat(row.updatedAt()).isNull();
        assertThat(history.total()).isEqualTo(2);
        assertThat(history.items().get(0).previousValue()).isEqualTo(12);
        assertThat(history.items().get(0).newValue()).isNull();
        assertThat(history.items().get(0).changedBy()).isEqualTo(operator.email());
    }

    @Test
    void pagedHistoryNewestFirst() {
        for (int value = 1; value <= 25; value++) {
            put(PAGE_LIMIT, "{\"value\":" + value + "}", operatorToken, Setting.class);
        }

        ChangePage first = get(BASE + "/" + PAGE_LIMIT + "/history?page=0&size=20", operatorToken, ChangePage.class)
                .getBody();
        ChangePage last = get(BASE + "/" + PAGE_LIMIT + "/history?page=1&size=20", operatorToken, ChangePage.class)
                .getBody();

        assertThat(first.total()).isEqualTo(25);
        assertThat(first.items()).hasSize(20);
        assertThat(first.items().get(0).newValue()).isEqualTo(25);
        assertThat(first.items().get(0).previousValue()).isEqualTo(24);
        assertThat(last.items()).hasSize(5);
        assertThat(last.items().get(4).newValue()).isEqualTo(1);
        assertThat(last.items().get(4).previousValue()).isNull();
        assertThat(last.total()).isEqualTo(25);
    }

    @Test
    void pageSizeOverTheMaximumIs400() {
        ResponseEntity<Problem> response = get(BASE + "/" + LIMIT + "/history?size=101", operatorToken,
                Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
    }

    @Test
    void staffWithoutTheKeyIs403() {
        UserView support = userService.createUser(owner.id(), email("settings-support"), PASSWORD, "Support",
                "CUSTOMER_SUPPORT");
        String token = login(support.email());

        assertThat(get(BASE, token, Problem.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(BASE + "/" + LIMIT + "/history", token, Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(LIMIT, "{\"value\":12}", token, Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(BASE, null, Problem.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aDeletedAdminReadsNull() {
        UserView temporary = userService.createUser(owner.id(), email("settings-temporary"), PASSWORD, "Temporary",
                "PLATFORM_OPERATOR");
        put(LIMIT, "{\"value\":12}", login(temporary.email()), Setting.class);
        jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", temporary.id());
        jdbc.update("DELETE FROM app_user WHERE id = ?", temporary.id());

        Setting row = ownKeys(get(BASE, operatorToken, SettingList.class).getBody()).get(2);
        ChangePage history = get(BASE + "/" + LIMIT + "/history", operatorToken, ChangePage.class).getBody();

        assertThat(row.source()).isEqualTo("STORED");
        assertThat(row.updatedBy()).isNull();
        assertThat(row.updatedAt()).isNotNull();
        assertThat(history.items()).hasSize(1);
        assertThat(history.items().get(0).changedBy()).isNull();
    }

    @Test
    void aStoredValueWithoutAnAuthorReadsNull() {
        jdbc.update("INSERT INTO app_setting (key, value, updated_by, updated_at) VALUES (?, 'false', NULL, now())",
                SWITCH_OFF);

        Setting row = ownKeys(get(BASE, operatorToken, SettingList.class).getBody()).get(0);
        ResponseEntity<Setting> sameValue = put(SWITCH_OFF, "{\"value\":false}", operatorToken, Setting.class);

        assertThat(row.source()).isEqualTo("STORED");
        assertThat(row.updatedBy()).isNull();
        assertThat(row.updatedAt()).isNotNull();
        assertThat(sameValue.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sameValue.getBody().updatedBy()).isNull();
        assertThat(sameValue.getBody().updatedAt()).isNotNull();
    }

    private static List<Setting> ownKeys(SettingList list) {
        return list.items().stream().filter(item -> "itapi".equals(item.group())).toList();
    }

    private long rows() {
        return jdbc.queryForObject("SELECT (SELECT count(*) FROM app_setting WHERE key LIKE 'itapi.%')"
                + " + (SELECT count(*) FROM app_setting_change WHERE key LIKE 'itapi.%')", Long.class);
    }

    private <T> ResponseEntity<T> get(String path, String token, Class<T> type) {
        return exchange(HttpMethod.GET, path, token, null, null, type);
    }

    private <T> ResponseEntity<T> put(String key, String json, String token, Class<T> type) {
        return put(key, json, token, type, null);
    }

    private <T> ResponseEntity<T> put(String key, String json, String token, Class<T> type, String language) {
        return exchange(HttpMethod.PUT, BASE + "/" + key, token, json, language, type);
    }

    private <T> ResponseEntity<T> exchange(HttpMethod method, String path, String token, String json,
            String language, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        if (json != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(json, headers), type);
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, PASSWORD),
                TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
