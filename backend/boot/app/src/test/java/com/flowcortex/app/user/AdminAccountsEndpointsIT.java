package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

/** {@code /api/v1/admin/accounts} and {@code /api/v1/admin/roles} against the real, fully-wired app. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.registration.owner-email=" + AdminAccountsEndpointsIT.REGISTRATION_OWNER)
@AutoConfigureTestRestTemplate
class AdminAccountsEndpointsIT extends AbstractUserModuleIT {

    /** Not the owner of {@code UserAuthEndpointsIT}: the database is shared and that account has another password. */
    static final String REGISTRATION_OWNER = "admin-accounts-registration-owner@example.com";
    private static final String PASSWORD = "correct-password-1";

    @Autowired
    private JdbcTemplate jdbc;

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private UserView seedUser(UserView admin, String label, String role) {
        return userService.createUser(admin.id(), uniqueEmail(label), PASSWORD, label, role);
    }

    private ResponseEntity<String> patchRole(UUID id, String token, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/admin/accounts/" + id + "/role", HttpMethod.PATCH,
                new HttpEntity<>(new RoleRequestView(role), headers), String.class);
    }

    private String roleOf(UUID id) {
        return jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, id);
    }

    @Test
    void ownRoleChangeIsConflict() {
        UserView admin = seedAdmin("admin-own-role");

        ResponseEntity<String> response = patchRole(admin.id(), login(admin.email(), PASSWORD), "MEMBER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("cannot change your own role");
        assertThat(roleOf(admin.id())).isEqualTo("ADMIN");
    }

    @Test
    void selfDeactivationIsConflict() {
        UserView admin = seedAdmin("admin-self-deactivate");

        ResponseEntity<String> response = post("/api/v1/admin/accounts/" + admin.id() + "/deactivate",
                login(admin.email(), PASSWORD), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("cannot deactivate your own");
    }

    @Test
    void deactivatingAMemberIsConflict() {
        UserView admin = seedAdmin("admin-deactivate-member");
        UserView member = seedUser(admin, "member-deactivate", "MEMBER");

        ResponseEntity<String> response = post("/api/v1/admin/accounts/" + member.id() + "/deactivate",
                login(admin.email(), PASSWORD), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("Only an admin account");
    }

    @Test
    void anUnknownRoleIsBadRequest() {
        UserView admin = seedAdmin("admin-unknown-role");
        UserView staff = seedUser(admin, "staff-unknown-role", "CUSTOMER_SUPPORT");

        ResponseEntity<String> response = patchRole(staff.id(), login(admin.email(), PASSWORD), "OWNER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("role");
        assertThat(roleOf(staff.id())).isEqualTo("CUSTOMER_SUPPORT");
    }

    @Test
    void deactivationIsIdempotentAndKeepsTheFirstTime() {
        UserView admin = seedAdmin("admin-idempotent");
        UserView staff = seedUser(admin, "staff-idempotent", "OPERATION_MANAGER");
        String token = login(admin.email(), PASSWORD);

        ResponseEntity<AccountView> first = post("/api/v1/admin/accounts/" + staff.id() + "/deactivate", token,
                null, AccountView.class);
        ResponseEntity<AccountView> second = post("/api/v1/admin/accounts/" + staff.id() + "/deactivate", token,
                null, AccountView.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().adminDisabled()).isTrue();
        assertThat(second.getBody().adminDisabledAt()).isEqualTo(first.getBody().adminDisabledAt());
        assertThat(jdbc.queryForObject("SELECT admin_disabled_by FROM app_user WHERE id = ?", UUID.class,
                staff.id())).isEqualTo(admin.id());

        ResponseEntity<AccountView> reactivated = post("/api/v1/admin/accounts/" + staff.id() + "/activate", token,
                null, AccountView.class);

        assertThat(reactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reactivated.getBody().adminDisabled()).isFalse();
    }

    @Test
    void demotingTheRegistrationOwnerIsConflict() {
        UserView owner = userService.createInitialAdmin(REGISTRATION_OWNER, PASSWORD, "Registration Owner");
        UserView other = seedAdmin("admin-other-than-owner");

        ResponseEntity<String> response = patchRole(owner.id(), login(other.email(), PASSWORD),
                "OPERATION_MANAGER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("owns self-registration");
        assertThat(roleOf(owner.id())).isEqualTo("ADMIN");
    }

    @Test
    void listFiltersByRoleAndSearchesNameOrEmailAcrossOwnerScopes() {
        UserView admin = seedAdmin("admin-list");
        UserView otherScope = seedAdmin("admin-list-other-scope");
        UserView linh = userService.createUser(otherScope.id(), uniqueEmail("linh-list"), PASSWORD, "Linh List",
                "CUSTOMER_SUPPORT");
        seedUser(admin, "minh-list", "OPERATION_MANAGER");
        UserView member = seedUser(admin, "ba-list", "MEMBER");
        String token = login(admin.email(), PASSWORD);

        ResponseEntity<PageView> filtered = get("/api/v1/admin/accounts?role=CUSTOMER_SUPPORT&q=LINH-LIST", token,
                PageView.class);
        ResponseEntity<PageView> everything = get("/api/v1/admin/accounts?size=100", token, PageView.class);

        assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(filtered.getBody().total()).isEqualTo(1);
        assertThat(filtered.getBody().items()).extracting(AccountView::email).containsExactly(linh.email());
        assertThat(everything.getBody().items()).extracting(AccountView::email)
                .doesNotContain(member.email());
    }

    @Test
    void listRejectsAPageSizeOutOfRange() {
        UserView admin = seedAdmin("admin-list-range");
        String token = login(admin.email(), PASSWORD);

        assertThat(get("/api/v1/admin/accounts?size=101", token, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/admin/accounts?page=-1", token, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rolesListsTheSevenRolesWithTheirKeys() {
        UserView admin = seedAdmin("admin-roles");
        UserView manager = seedUser(admin, "manager-roles", "OPERATION_MANAGER");

        ResponseEntity<List<RoleView>> response = restTemplate.exchange("/api/v1/admin/roles", HttpMethod.GET,
                bearer(login(admin.email(), PASSWORD)), new org.springframework.core.ParameterizedTypeReference<>() {
                });
        ResponseEntity<String> refused = get("/api/v1/admin/roles", login(manager.email(), PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<RoleView> roles = response.getBody();
        assertThat(roles).extracting(RoleView::code).containsExactly("ADMIN", "OPERATION_MANAGER",
                "CUSTOMER_SUPPORT", "CONTENT_EDITOR", "PLATFORM_OPERATOR", "MEMBER", "CREATOR");
        assertThat(roles.get(0).superAdmin()).isTrue();
        assertThat(roles.get(0).permissions()).hasSize(com.flowcortex.shared.security.authz.Permission.values().length);
        assertThat(roles.get(5).staff()).isFalse();
        assertThat(roles.get(5).permissions()).isEmpty();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    record RoleRequestView(String role) {
    }

    record AccountView(UUID id, String email, String name, String role, String status, boolean adminDisabled,
            Instant adminDisabledAt, Instant createdAt) {
    }

    record PageView(List<AccountView> items, int page, int size, long total) {
    }

    record RoleView(String code, boolean staff, boolean superAdmin, List<String> permissions) {
    }
}
