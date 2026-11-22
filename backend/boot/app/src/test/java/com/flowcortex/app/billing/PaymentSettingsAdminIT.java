package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.settings.PaymentSettingGroup;
import com.flowcortex.billing.settings.PaymentSettingMeta;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.infrastructure.SettingStore;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.time.Instant;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The payment settings console API over HTTP against Postgres. The two payment keys are declared here, so
 * the cases never depend on which keys the feature modules declare beyond the region gate's.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
class PaymentSettingsAdminIT extends AbstractPostgresBootTest {

    private static final String BASE = "/api/v1/admin/payment-settings";
    private static final String PASSWORD = "password123";
    private static final String MODE = "payment.test.mode";
    private static final String LIMIT = "payment.test.limit";
    private static final String REGION_GATE = "payment.region_gate.mode";
    private static final String GENERAL = "itpay.general.flag";
    private static final String CREDENTIAL = "FLOWCORTEX_MOMO_SECRET_KEY";
    private static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");

    @TestConfiguration
    static class Keys {

        @Bean
        SettingDefinition<String> itPaymentMode() {
            return SettingDefinition.enumKey(MODE, List.of("a", "b", "c"), "a", () -> "a")
                    .inScope(SettingScope.PAYMENT_OPS);
        }

        @Bean
        SettingDefinition<Integer> itPaymentLimit() {
            return SettingDefinition.integerKey(LIMIT, 1, 100, () -> 10).inScope(SettingScope.PAYMENT_OPS);
        }

        @Bean
        SettingDefinition<Boolean> itGeneralFlag() {
            return SettingDefinition.switchKey(GENERAL, false, () -> null);
        }

        @Bean
        PaymentSettingMeta itPaymentModeMeta() {
            return new PaymentSettingMeta(MODE, PaymentSettingGroup.REGION, true);
        }

        @Bean
        PaymentSettingMeta itPaymentLimitMeta() {
            return new PaymentSettingMeta(LIMIT, PaymentSettingGroup.REGION, false);
        }
    }

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record PaymentSetting(String key, String group, String type, Object value, String source, Object storedValue,
            Object configuredValue, Object defaultValue, boolean dangerous, List<String> options, Integer min,
            Integer max) {
    }

    record PaymentChange(UUID id, String key, String previousValue, String newValue, UUID actorId,
            String actorEmail, String reason, Instant changedAt) {
    }

    record ChangePage(List<PaymentChange> items, int page, int size, long total) {
    }

    record Problem(int status, URI type, String detail, String code, Map<String, String> errors) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private SettingStore store;

    private UserView owner;
    private String adminToken;
    private String managerToken;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'payment.test.%' OR key = ?", GENERAL);
        jdbc.update("DELETE FROM app_setting_change WHERE key LIKE 'payment.test.%' OR key = ?", GENERAL);
        owner = userService.createInitialAdmin(email("payment-owner"), PASSWORD, "Owner");
        adminToken = login(owner.email());
        managerToken = login(staff("payment-manager", "OPERATION_MANAGER").email());
    }

    @Test
    void listShowsOnlyPaymentKeysWithTheirSources() {
        put(MODE, "{\"value\":\"b\",\"reason\":\"contract\"}", adminToken, PaymentSetting.class);

        ResponseEntity<PaymentSetting[]> response = get(BASE, managerToken, PaymentSetting[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<PaymentSetting> all = List.of(response.getBody());
        assertThat(all).extracting(PaymentSetting::key).contains(REGION_GATE).doesNotContain(GENERAL);
        List<PaymentSetting> items = all.stream().filter(item -> item.key().startsWith("payment.test.")).toList();
        assertThat(items).extracting(PaymentSetting::key).containsExactly(LIMIT, MODE);
        PaymentSetting limit = items.get(0);
        assertThat(limit.group()).isEqualTo("region");
        assertThat(limit.type()).isEqualTo("INTEGER");
        assertThat(limit.value()).isEqualTo(10);
        assertThat(limit.source()).isEqualTo("CONFIGURED");
        assertThat(limit.storedValue()).isNull();
        assertThat(limit.configuredValue()).isEqualTo(10);
        assertThat(limit.dangerous()).isFalse();
        assertThat(limit.min()).isEqualTo(1);
        assertThat(limit.max()).isEqualTo(100);
        PaymentSetting mode = items.get(1);
        assertThat(mode.type()).isEqualTo("ENUM");
        assertThat(mode.value()).isEqualTo("b");
        assertThat(mode.source()).isEqualTo("STORED");
        assertThat(mode.storedValue()).isEqualTo("b");
        assertThat(mode.configuredValue()).isEqualTo("a");
        assertThat(mode.defaultValue()).isEqualTo("a");
        assertThat(mode.dangerous()).isTrue();
        assertThat(mode.options()).containsExactly("a", "b", "c");
        assertThat(mode.min()).isNull();
    }

    @Test
    void acceptedChangeStoresAndRecordsOneRowWithTrimmedReason() {
        ResponseEntity<PaymentSetting> response = put(MODE, "{\"value\":\"b\",\"reason\":\"  contract  \"}",
                adminToken, PaymentSetting.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().value()).isEqualTo("b");
        assertThat(response.getBody().source()).isEqualTo("STORED");
        assertThat(jdbc.queryForObject("SELECT value FROM app_setting WHERE key = ?", String.class, MODE))
                .isEqualTo("b");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT previous_value, new_value, changed_by, reason FROM app_setting_change WHERE key = ?", MODE);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("previous_value")).isNull();
        assertThat(rows.get(0).get("new_value")).isEqualTo("b");
        assertThat(rows.get(0).get("changed_by")).isEqualTo(owner.id());
        assertThat(rows.get(0).get("reason")).isEqualTo("contract");
    }

    @Test
    void sameValueAgainRecordsNothing() {
        put(MODE, "{\"value\":\"b\",\"reason\":\"first\"}", adminToken, PaymentSetting.class);

        ResponseEntity<PaymentSetting> again = put(MODE, "{\"value\":\"b\",\"reason\":\"again\"}", adminToken,
                PaymentSetting.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().value()).isEqualTo("b");
        assertThat(rows(MODE)).isEqualTo(1);
    }

    @Test
    void aNonDangerousKeyTakesNoReasonAndStoresABlankOneAsNull() {
        ResponseEntity<PaymentSetting> response = put(LIMIT, "{\"value\":42,\"reason\":\"   \"}", adminToken,
                PaymentSetting.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().value()).isEqualTo(42);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_setting_change WHERE key = ? AND reason IS NULL",
                Long.class, LIMIT)).isEqualTo(1);
    }

    @Test
    void failedHistoryInsertLeavesTheStoredValue() {
        put(MODE, "{\"value\":\"b\",\"reason\":\"first\"}", adminToken, PaymentSetting.class);
        doThrow(new IllegalStateException("history is down")).when(store)
                .insertChange(any(), eq(MODE), any(), any(), any(), any(), any());

        ResponseEntity<Problem> response = put(MODE, "{\"value\":\"c\",\"reason\":\"second\"}", adminToken,
                Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(jdbc.queryForObject("SELECT value FROM app_setting WHERE key = ?", String.class, MODE))
                .isEqualTo("b");
        assertThat(rows(MODE)).isEqualTo(1);
    }

    @Test
    void refusalOrder() {
        ResponseEntity<Problem> unknown = put("payment.no_such.key", "{\"value\":\"maybe\"}", adminToken,
                Problem.class);
        ResponseEntity<Problem> noReason = put(MODE, "{\"value\":\"OFF\",\"reason\":\"   \"}", adminToken,
                Problem.class);
        ResponseEntity<Problem> badValue = put(MODE, "{\"value\":\"OFF\",\"reason\":\"test\"}", adminToken,
                Problem.class);
        ResponseEntity<Problem> nullValue = put(MODE, "{\"value\":null,\"reason\":\"x\"}", adminToken,
                Problem.class);
        ResponseEntity<Problem> vietnamese = put(MODE, "{\"value\":\"c\"}", adminToken, Problem.class, "vi");

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody().type()).isEqualTo(PROBLEMS.resolve("not-found"));
        assertThat(unknown.getBody().detail()).isEqualTo("No such payment setting");
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noReason.getBody().code()).isEqualTo("paymentSetting.reasonRequired");
        assertThat(noReason.getBody().errors()).containsOnlyKeys("reason");
        assertThat(badValue.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(badValue.getBody().code()).isEqualTo("setting.invalidValue");
        assertThat(badValue.getBody().errors().get("value")).isEqualTo("Must be one of: a, b, c");
        assertThat(nullValue.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(nullValue.getBody().code()).isEqualTo("setting.invalidValue");
        assertThat(nullValue.getBody().errors().get("value")).isEqualTo("Must be one of: a, b, c");
        assertThat(vietnamese.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(vietnamese.getBody().errors().get("reason")).isEqualTo("Thay đổi này cần có lý do");
        assertThat(rows(MODE)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_setting WHERE key LIKE 'payment.%'", Long.class))
                .isZero();
    }

    @Test
    void malformedBodyIs400() {
        ResponseEntity<Problem> missingValue = put(MODE, "{}", adminToken, Problem.class);
        ResponseEntity<Problem> longReason = put(MODE, "{\"value\":\"b\",\"reason\":\"" + "x".repeat(2001) + "\"}",
                adminToken, Problem.class);
        ResponseEntity<Problem> notJson = put(MODE, "{\"value\":", adminToken, Problem.class);

        assertThat(missingValue.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingValue.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
        assertThat(missingValue.getBody().errors()).containsKey("value");
        assertThat(longReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longReason.getBody().errors()).containsKey("reason");
        assertThat(notJson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rows(MODE)).isZero();
    }

    @Test
    void otherScopeUndeclaredAndCredentialKeysAre404WithTheSameDetail() {
        List<String> keys = List.of(GENERAL, "no.such.key", CREDENTIAL);

        for (String key : keys) {
            ResponseEntity<Problem> update = put(key, "{\"value\":true,\"reason\":\"x\"}", adminToken,
                    Problem.class);
            ResponseEntity<Problem> history = get(BASE + "/history?key=" + key, adminToken, Problem.class);

            assertThat(update.getStatusCode()).as(key).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(history.getStatusCode()).as(key).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(update.getBody().detail()).isEqualTo("No such payment setting");
            assertThat(history.getBody().detail()).isEqualTo(update.getBody().detail());
            assertThat(history.getBody().type()).isEqualTo(PROBLEMS.resolve("not-found"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_setting WHERE key = ?", Long.class, GENERAL))
                .isZero();
        assertThat(rows(GENERAL)).isZero();
    }

    @Test
    void historyNewestFirstAcrossPaymentKeysOnly() {
        put(MODE, "{\"value\":\"b\",\"reason\":\"one\"}", adminToken, PaymentSetting.class);
        put(LIMIT, "{\"value\":5}", adminToken, PaymentSetting.class);
        put(MODE, "{\"value\":\"c\",\"reason\":\"three\"}", adminToken, PaymentSetting.class);
        exchange(HttpMethod.PUT, "/api/v1/admin/settings/" + GENERAL, adminToken, "{\"value\":true}", null,
                String.class);

        ChangePage page = get(BASE + "/history", managerToken, ChangePage.class).getBody();

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(25);
        assertThat(page.items()).extracting(PaymentChange::key).containsExactly(MODE, LIMIT, MODE);
        assertThat(page.items()).extracting(PaymentChange::newValue).containsExactly("c", "5", "b");
        assertThat(page.items().get(0).previousValue()).isEqualTo("b");
        assertThat(page.items().get(0).reason()).isEqualTo("three");
        assertThat(page.items().get(0).actorId()).isEqualTo(owner.id());
        assertThat(page.items().get(0).actorEmail()).isEqualTo(owner.email());
        assertThat(page.items().get(2).previousValue()).isNull();
        assertThat(page.items()).extracting(PaymentChange::key).doesNotContain(GENERAL);
    }

    @Test
    void historyFilterByKeyAndDeletedActorEmailIsNull() {
        put(MODE, "{\"value\":\"b\",\"reason\":\"one\"}", adminToken, PaymentSetting.class);
        put(LIMIT, "{\"value\":5}", adminToken, PaymentSetting.class);
        UUID gone = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_setting_change (id, key, previous_value, new_value, changed_by, changed_at, reason)
                VALUES (?, ?, 'b', 'c', ?, now() + interval '1 minute', 'old')
                """, UUID.randomUUID(), MODE, gone);

        ChangePage page = get(BASE + "/history?key=" + MODE + "&page=0&size=1", adminToken, ChangePage.class)
                .getBody();
        ChangePage second = get(BASE + "/history?key=" + MODE + "&page=1&size=1", adminToken, ChangePage.class)
                .getBody();

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).actorId()).isEqualTo(gone);
        assertThat(page.items().get(0).actorEmail()).isNull();
        assertThat(second.items()).extracting(PaymentChange::actorEmail).containsExactly(owner.email());
        assertThat(second.items()).extracting(PaymentChange::key).containsExactly(MODE);
    }

    @Test
    void historySizeOver100Is400() {
        ResponseEntity<Problem> response = get(BASE + "/history?size=101", adminToken, Problem.class);
        ResponseEntity<Problem> negative = get(BASE + "/history?page=-1", adminToken, Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
        assertThat(response.getBody().errors()).containsKey("size");
        assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(negative.getBody().errors()).containsKey("page");
    }

    @Test
    void operationManagerReadsButCannotChange() {
        assertThat(get(BASE, managerToken, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(BASE + "/history", managerToken, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Problem> change = put(MODE, "{\"value\":\"b\",\"reason\":\"x\"}", managerToken,
                Problem.class);

        assertThat(change.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(change.getBody().type()).isEqualTo(PROBLEMS.resolve("forbidden"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_setting WHERE key = ?", Long.class, MODE)).isZero();
        assertThat(rows(MODE)).isZero();
    }

    @Test
    void platformOperatorAndCustomerSupportAreForbidden() {
        for (String role : List.of("PLATFORM_OPERATOR", "CUSTOMER_SUPPORT")) {
            String token = login(staff("payment-" + role.toLowerCase().replace('_', '-'), role).email());

            assertThat(get(BASE, token, Problem.class).getStatusCode()).as(role).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(BASE + "/history", token, Problem.class).getStatusCode()).as(role)
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(put(MODE, "{\"value\":\"b\",\"reason\":\"x\"}", token, Problem.class).getStatusCode())
                    .as(role).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(get(BASE, null, Problem.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rows(MODE)).isZero();
    }

    @Test
    void logLineNamesKeyAndActorOnly(CapturedOutput output) {
        put(MODE, "{\"value\":\"c\",\"reason\":\"reason-that-must-not-leak\"}", adminToken, PaymentSetting.class);

        List<String> lines = output.getOut().lines().filter(line -> line.contains("payment setting written"))
                .toList();

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).endsWith("payment setting written key=" + MODE + " actor=" + owner.id());
        assertThat(output.getAll()).doesNotContain("reason-that-must-not-leak");
    }

    private UserView staff(String prefix, String role) {
        return userService.createUser(owner.id(), email(prefix), PASSWORD, prefix, role);
    }

    private long rows(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM app_setting_change WHERE key = ?", Long.class, key);
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
