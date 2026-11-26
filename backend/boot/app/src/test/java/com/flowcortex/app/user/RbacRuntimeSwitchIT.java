package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.roles.RbacMutationSwitch;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import org.testcontainers.junit.jupiter.Testcontainers;

/** The role-edit kill switch over real HTTP: what it refuses, what it never refuses, and its own route. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RbacRuntimeSwitchIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String KEY = "user.rbac.mutations_enabled";
    private static final String RUNTIME = "/api/v1/admin/rbac/runtime";
    private static final String ROLES = "/api/v1/admin/roles";
    private static final String DISABLED = "Role and permission changes are turned off";

    @Autowired
    private JdbcTemplate jdbc;

    private final ListAppender<ILoggingEvent> refusals = new ListAppender<>();
    private final Logger switchLogger = (Logger) LoggerFactory.getLogger(RbacMutationSwitch.class);

    @BeforeEach
    void captureRefusals() {
        clean();
        refusals.start();
        switchLogger.addAppender(refusals);
    }

    @AfterEach
    void clean() {
        switchLogger.detachAppender(refusals);
        refusals.stop();
        jdbc.update("DELETE FROM app_setting WHERE key = ?", KEY);
        jdbc.update("DELETE FROM app_setting_change WHERE key = ?", KEY);
        jdbc.update("DELETE FROM app_user WHERE role IN (SELECT code FROM role WHERE NOT system)");
        jdbc.update("DELETE FROM role WHERE NOT system");
    }

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private UserView seedUser(UserView admin, String label, String role) {
        return userService.createUser(admin.id(), uniqueEmail(label), PASSWORD, label, role);
    }

    private void seedCustomRole(String code, Permission... keys) {
        jdbc.update("INSERT INTO role (code, name, staff, super_admin, system, sort_order) "
                + "VALUES (?, ?, true, false, false, 1000)", code, code);
        for (Permission key : keys) {
            jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES (?, ?)", code, key.key());
        }
    }

    private ResponseEntity<RbacRuntimeView> setSwitch(String token, boolean enabled) {
        return put(RUNTIME, token, new RuntimeBody(enabled, null), RbacRuntimeView.class);
    }

    private ResponseEntity<Problem> exchange(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), Problem.class);
    }

    private ResponseEntity<String> rawPut(String path, String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(json, headers), String.class);
    }

    private String roleOf(UUID id) {
        return jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, id);
    }

    private List<String> roleTableState() {
        return jdbc.queryForList("SELECT code || ':' || name || ':' || permissions_version FROM role ORDER BY code",
                String.class);
    }

    private List<String> rolePermissionState() {
        return jdbc.queryForList("SELECT role_code || ':' || permission_key FROM role_permission "
                + "ORDER BY role_code, permission_key", String.class);
    }

    private void assertRefused(ResponseEntity<Problem> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().detail()).isEqualTo(DISABLED);
    }

    /** Distinct lines: the Apache test client retries a 503 once, so each refused request logs twice. */
    private List<String> refusalLines() {
        return refusals.list.stream().map(ILoggingEvent::getFormattedMessage).distinct().toList();
    }

    private int storedRows() {
        return jdbc.queryForObject("SELECT count(*) FROM app_setting WHERE key = ?", Integer.class, KEY);
    }

    private int historyRows() {
        return jdbc.queryForObject("SELECT count(*) FROM app_setting_change WHERE key = ?", Integer.class, KEY);
    }

    @Test
    void withTheSwitchOffEveryRoleMutationAnswers503AndChangesNothing() {
        UserView admin = seedAdmin("rbac-off-all");
        String token = login(admin.email(), PASSWORD);
        UserView lan = seedUser(admin, "lan", "OPERATION_MANAGER");
        UserView minh = seedUser(admin, "minh", "MEMBER");
        ResponseEntity<RoleView> created = post(ROLES, token,
                new CreateBody("Biên tập viên", null, List.of("support.ticket.view")), RoleView.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String custom = created.getBody().code();
        List<String> rolesBefore = roleTableState();
        List<String> keysBefore = rolePermissionState();
        assertThat(setSwitch(token, false).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertRefused(exchange(HttpMethod.POST, ROLES, token,
                new CreateBody("Biên tập viên hai", null, List.of())));
        assertRefused(exchange(HttpMethod.PATCH, ROLES + "/" + custom, token, Map.of("name", "Renamed")));
        assertRefused(exchange(HttpMethod.DELETE, ROLES + "/" + custom, token, null));
        assertRefused(exchange(HttpMethod.PUT, ROLES + "/" + custom + "/permissions", token,
                new ReplaceBody(List.of("support.ticket.view", "support.ticket.reply"), 0)));
        assertRefused(exchange(HttpMethod.PATCH, "/api/v1/admin/accounts/" + lan.id() + "/role", token,
                Map.of("role", "CUSTOMER_SUPPORT")));
        assertRefused(exchange(HttpMethod.PUT, "/api/v1/users/" + minh.id(), token, Map.of("role", "CREATOR")));

        assertThat(roleTableState()).isEqualTo(rolesBefore);
        assertThat(rolePermissionState()).isEqualTo(keysBefore);
        assertThat(roleOf(lan.id())).isEqualTo("OPERATION_MANAGER");
        assertThat(roleOf(minh.id())).isEqualTo("MEMBER");
        assertThat(refusalLines()).hasSize(6)
                .anySatisfy(line -> assertThat(line).contains("operation=ROLE_CREATE"))
                .anySatisfy(line -> assertThat(line).contains("operation=ROLE_UPDATE"))
                .anySatisfy(line -> assertThat(line).contains("operation=ROLE_DELETE"))
                .anySatisfy(line -> assertThat(line).contains("operation=ROLE_PERMISSIONS_REPLACE"))
                .anySatisfy(line -> assertThat(line).contains("operation=ACCOUNT_ROLE_CHANGE"))
                .anySatisfy(line -> assertThat(line).contains("operation=USER_ROLE_CHANGE"))
                .allSatisfy(line -> assertThat(line)
                        .startsWith("rbac.mutation outcome=refused_switch_off")
                        .contains("actor=" + admin.id()));
    }

    @Test
    void breakGlassAndReadsKeepWorkingWhileOff() {
        UserView admin = seedAdmin("rbac-break-glass");
        String token = login(admin.email(), PASSWORD);
        UserView lan = seedUser(admin, "lan", "OPERATION_MANAGER");
        UserView minh = seedUser(admin, "minh", "MEMBER");
        assertThat(setSwitch(token, false).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(get(ROLES, token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/admin/permission-catalog", token, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/api/v1/admin/accounts/" + lan.id() + "/deactivate", token, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/v1/admin/accounts/" + lan.id() + "/activate", token, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> rename = put("/api/v1/users/" + minh.id(), token,
                Map.of("name", "Minh Renamed", "role", "MEMBER"), String.class);
        ResponseEntity<RbacRuntimeView> runtime = get(RUNTIME, token, RbacRuntimeView.class);

        assertThat(rename.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runtime.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runtime.getBody().mutationsEnabled()).isFalse();
        assertThat(refusalLines()).isEmpty();
    }

    @Test
    void aCallerWithoutTheManageKeyGets403Not503() {
        UserView admin = seedAdmin("rbac-403");
        seedCustomRole("IT_USER_EDITOR", Permission.USER_ACCOUNT_UPDATE, Permission.USER_ACCOUNT_CHANGE_ROLE);
        UserView tu = seedUser(admin, "tu", "IT_USER_EDITOR");
        UserView manager = seedUser(admin, "manager", "OPERATION_MANAGER");
        UserView minh = seedUser(admin, "minh", "MEMBER");
        assertThat(setSwitch(login(admin.email(), PASSWORD), false).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Problem> createRole = exchange(HttpMethod.POST, ROLES, login(manager.email(), PASSWORD),
                new CreateBody("Nope", null, List.of()));
        ResponseEntity<Problem> giveStaffRole = exchange(HttpMethod.PUT, "/api/v1/users/" + minh.id(),
                login(tu.email(), PASSWORD), Map.of("role", "PLATFORM_OPERATOR"));

        assertThat(createRole.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(giveStaffRole.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(roleOf(minh.id())).isEqualTo("MEMBER");
        assertThat(refusalLines()).isEmpty();
    }

    @Test
    void turningTheSwitchBackOnAcceptsTheNextCreate() {
        UserView admin = seedAdmin("rbac-back-on");
        String token = login(admin.email(), PASSWORD);
        setSwitch(token, false);
        assertRefused(exchange(HttpMethod.POST, ROLES, token, new CreateBody("Biên tập viên", null, List.of())));

        assertThat(setSwitch(token, true).getBody().mutationsEnabled()).isTrue();
        ResponseEntity<RoleView> created = post(ROLES, token, new CreateBody("Biên tập viên", null, List.of()),
                RoleView.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role WHERE code = 'BIEN_TAP_VIEN'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aStaffAccountIsRefusedAndAMemberAccountIsCreatedWhileOff() {
        UserView admin = seedAdmin("rbac-create-user");
        String token = login(admin.email(), PASSWORD);
        setSwitch(token, false);
        String staffEmail = uniqueEmail("ops");
        String memberEmail = uniqueEmail("lan");

        ResponseEntity<Problem> staff = exchange(HttpMethod.POST, "/api/v1/users", token,
                new CreateUserBody(staffEmail, PASSWORD, "Ops", "PLATFORM_OPERATOR"));
        ResponseEntity<String> member = post("/api/v1/users", token,
                new CreateUserBody(memberEmail, PASSWORD, "Lan", "MEMBER"), String.class);

        assertRefused(staff);
        assertThat(member.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, staffEmail))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, memberEmail))
                .isEqualTo(1);
        assertThat(refusalLines()).singleElement().asString().contains("operation=STAFF_ACCOUNT_CREATE");
    }

    @Test
    void aPutRecordsOneHistoryRowWithTheTrimmedReasonAndTheSameValueRecordsNone() {
        UserView admin = seedAdmin("rbac-put");
        String token = login(admin.email(), PASSWORD);

        ResponseEntity<RbacRuntimeView> first = put(RUNTIME, token,
                new RuntimeBody(false, "  Investigating a role grant  "), RbacRuntimeView.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        RbacRuntimeView body = first.getBody();
        assertThat(body.mutationsEnabled()).isFalse();
        assertThat(body.source()).isEqualTo("STORED");
        assertThat(body.configuredValue()).isNull();
        assertThat(body.updatedBy()).isEqualTo(admin.email());
        assertThat(body.updatedAt()).isNotNull();
        assertThat(jdbc.queryForMap("SELECT previous_value, new_value, changed_by, reason FROM app_setting_change "
                + "WHERE key = ?", KEY)).containsEntry("previous_value", null).containsEntry("new_value", "false")
                .containsEntry("changed_by", admin.id()).containsEntry("reason", "Investigating a role grant");

        ResponseEntity<RbacRuntimeView> again = setSwitch(token, false);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().mutationsEnabled()).isFalse();
        assertThat(historyRows()).isEqualTo(1);
        assertThat(get(RUNTIME, token, RbacRuntimeView.class).getBody()).isEqualTo(again.getBody());
    }

    @Test
    void theGeneralSettingsSurfaceAnswers404ForTheRbacKey() {
        UserView admin = seedAdmin("rbac-general");
        UserView minh = seedUser(admin, "minh", "PLATFORM_OPERATOR");
        String operator = login(minh.email(), PASSWORD);
        setSwitch(login(admin.email(), PASSWORD), false);
        String base = "/api/v1/admin/settings";

        ResponseEntity<SettingList> list = get(base, operator, SettingList.class);
        ResponseEntity<Problem> update = exchange(HttpMethod.PUT, base + "/" + KEY, operator, Map.of("value", true));
        ResponseEntity<Problem> reset = exchange(HttpMethod.DELETE, base + "/" + KEY, operator, null);
        ResponseEntity<Problem> history = exchange(HttpMethod.GET, base + "/" + KEY + "/history", operator, null);
        ResponseEntity<Problem> runtimeRead = exchange(HttpMethod.GET, RUNTIME, operator, null);
        ResponseEntity<Problem> runtimeWrite = exchange(HttpMethod.PUT, RUNTIME, operator,
                new RuntimeBody(true, null));

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().items()).extracting(ListedSetting::key).doesNotContain(KEY);
        for (ResponseEntity<Problem> response : List.of(update, reset, history)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(runtimeRead.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(runtimeWrite.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(storedRows()).isEqualTo(1);
        assertThat(historyRows()).isEqualTo(1);
        assertThat(get(RUNTIME, login(admin.email(), PASSWORD), RbacRuntimeView.class).getBody().mutationsEnabled())
                .isFalse();
    }

    @Test
    void aMalformedBodyIs400() {
        UserView admin = seedAdmin("rbac-malformed");
        String token = login(admin.email(), PASSWORD);

        ResponseEntity<String> notABoolean = rawPut(RUNTIME, token, "{\"mutationsEnabled\":\"no\"}");
        ResponseEntity<String> longReason = rawPut(RUNTIME, token,
                "{\"mutationsEnabled\":false,\"reason\":\"" + "x".repeat(2001) + "\"}");
        ResponseEntity<String> missing = rawPut(RUNTIME, token, "{}");

        for (ResponseEntity<String> response : List.of(notABoolean, longReason, missing)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(storedRows()).isZero();
        assertThat(historyRows()).isZero();
    }

    @Test
    void anEmptyEnvironmentValueDefersToTheDefault() {
        UserView admin = seedAdmin("rbac-default");

        ResponseEntity<RbacRuntimeView> runtime = get(RUNTIME, login(admin.email(), PASSWORD),
                RbacRuntimeView.class);

        assertThat(runtime.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runtime.getBody()).isEqualTo(new RbacRuntimeView(true, "DEFAULT", null, null, null));
    }

    record RuntimeBody(boolean mutationsEnabled, String reason) {
    }

    record RbacRuntimeView(boolean mutationsEnabled, String source, Boolean configuredValue, String updatedBy,
            Instant updatedAt) {
    }

    record CreateBody(String name, String description, List<String> permissionKeys) {
    }

    record ReplaceBody(List<String> keys, int expectedVersion) {
    }

    record CreateUserBody(String email, String password, String name, String role) {
    }

    record RoleView(String code, String name) {
    }

    record ListedSetting(String key) {
    }

    record SettingList(List<ListedSetting> items) {
    }

    record Problem(int status, String detail) {
    }
}
