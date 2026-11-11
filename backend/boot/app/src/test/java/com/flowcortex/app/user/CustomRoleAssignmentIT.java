package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.application.ConstraintViolations;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A custom role row, seeded with SQL, is assignable and decides access exactly like a system staff role. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class CustomRoleAssignmentIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AppUserRepository users;

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private String seedCustomRole(String code, String name, Permission... keys) {
        jdbc.update("INSERT INTO role (code, name, staff, super_admin, system, sort_order) "
                + "VALUES (?, ?, true, false, false, 1000)", code, name);
        for (Permission key : keys) {
            jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES (?, ?)", code, key.key());
        }
        return code;
    }

    private void dropCustomRole(String code) {
        jdbc.update("DELETE FROM app_user WHERE role = ?", code);
        jdbc.update("DELETE FROM role WHERE code = ?", code);
    }

    private ResponseEntity<String> patchRole(UUID id, String token, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/admin/accounts/" + id + "/role", HttpMethod.PATCH,
                new HttpEntity<>(new RoleBody(role), headers), String.class);
    }

    private ResponseEntity<String> createUserOver(String token, String role) {
        return post("/api/v1/users", token,
                new CreateUserRequestView(uniqueEmail("created"), PASSWORD, "Created", role), String.class);
    }

    private String roleOf(UUID id) {
        return jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, id);
    }

    @Test
    void aCustomRoleHolderIsDecidedByItsKeysOnTheNextRequest() {
        String code = seedCustomRole("IT_SUPPORT_L2", "Support level 2", Permission.SUPPORT_TICKET_VIEW,
                Permission.SUPPORT_TICKET_REPLY);
        try {
            UserView admin = seedAdmin("admin-custom-role");
            UserView minh = userService.createUser(admin.id(), uniqueEmail("minh"), PASSWORD, "Minh", "MEMBER");
            String token = login(minh.email(), PASSWORD);

            ResponseEntity<String> assigned = patchRole(minh.id(), login(admin.email(), PASSWORD), code);

            assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get("/api/v1/admin/support/requests", token, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(get("/api/v1/admin/runs", token, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            ResponseEntity<MeKeysView> me = get("/api/v1/auth/me", token, MeKeysView.class);
            assertThat(me.getBody().permissions())
                    .containsExactly("support.ticket.reply", "support.ticket.view");
        } finally {
            dropCustomRole(code);
        }
    }

    @Test
    void assigningAnUnknownCodeIsBadRequest() {
        UserView admin = seedAdmin("admin-unknown-code");
        UserView staff = userService.createUser(admin.id(), uniqueEmail("staff"), PASSWORD, "Staff",
                "CUSTOMER_SUPPORT");
        String token = login(admin.email(), PASSWORD);

        ResponseEntity<String> viaAccounts = patchRole(staff.id(), token, "NOPE");
        ResponseEntity<String> viaCreate = createUserOver(token, "NOPE");
        ResponseEntity<String> viaUpdate = restTemplate.exchange("/api/v1/users/" + staff.id(), HttpMethod.PUT,
                bearer(token, new UpdateUserRequestView(null, null, "NOPE", null)), String.class);

        assertThat(viaAccounts.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(viaAccounts.getBody()).contains("NOPE");
        assertThat(viaCreate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(viaUpdate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(roleOf(staff.id())).isEqualTo("CUSTOMER_SUPPORT");
    }

    @Test
    void anAssignmentThatLosesToARoleDeleteIsBadRequest() {
        UserView admin = seedAdmin("admin-lost-race");
        UserView staff = userService.createUser(admin.id(), uniqueEmail("staff"), PASSWORD, "Staff",
                "CUSTOMER_SUPPORT");
        String token = login(admin.email(), PASSWORD);
        String code = seedCustomRole("IT_GONE_ROLE", "Gone role", Permission.SUPPORT_TICKET_VIEW);
        jdbc.update("DELETE FROM role WHERE code = ?", code);

        ResponseEntity<String> viaAccounts = patchRole(staff.id(), token, code);
        ResponseEntity<String> viaCreate = createUserOver(token, code);

        assertThat(viaAccounts.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(viaCreate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(roleOf(staff.id())).isEqualTo("CUSTOMER_SUPPORT");
    }

    @Test
    void anAssignmentThatLosesToAnInFlightRoleDeleteIsBadRequest() throws Exception {
        UserView admin = seedAdmin("admin-inflight-race");
        UserView staff = userService.createUser(admin.id(), uniqueEmail("staff"), PASSWORD, "Staff",
                "CUSTOMER_SUPPORT");
        String token = login(admin.email(), PASSWORD);
        String code = seedCustomRole("IT_RACED_ROLE", "Raced role", Permission.SUPPORT_TICKET_VIEW);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection deleter = jdbc.getDataSource().getConnection()) {
            deleter.setAutoCommit(false);
            try (PreparedStatement delete = deleter.prepareStatement("DELETE FROM role WHERE code = ?")) {
                delete.setString(1, code);
                delete.executeUpdate();
            }
            Future<ResponseEntity<String>> assignment = pool.submit(() -> patchRole(staff.id(), token, code));
            awaitUpdateBlockedOnTheRoleRow();

            deleter.commit();
            ResponseEntity<String> refused = assignment.get(30, TimeUnit.SECONDS);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody()).contains(code);
        } finally {
            pool.shutdownNow();
            dropCustomRole(code);
        }
        assertThat(roleOf(staff.id())).isEqualTo("CUSTOMER_SUPPORT");
    }

    private void awaitUpdateBlockedOnTheRoleRow() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND query ILIKE 'update app_user%'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the role update never waited on the role row");
    }

    @Test
    void theDriverNamesTheRoleForeignKeyWhenARowPointsAtAMissingRole() {
        UserView admin = seedAdmin("admin-fk-name");
        AppUser orphan = new AppUser(UUID.randomUUID(), admin.id(), uniqueEmail("orphan"), "$2a$hash", "Orphan",
                Role.of("NO_SUCH_ROLE"), UserStatus.ACTIVE);

        assertThatThrownBy(() -> users.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(ConstraintViolations.nameOf(error))
                        .contains(ConstraintViolations.APP_USER_ROLE_FK));
    }

    @Test
    void accountsCarryTheirRoleName() {
        String code = seedCustomRole("IT_NAMED_ROLE", "Điều phối nội dung", Permission.SUPPORT_TICKET_VIEW);
        try {
            UserView admin = seedAdmin("admin-role-name");
            UserView staff = userService.createUser(admin.id(), uniqueEmail("named"), PASSWORD, "Named", code);
            String token = login(admin.email(), PASSWORD);

            ResponseEntity<List<UserRow>> users = restTemplate.exchange("/api/v1/users", HttpMethod.GET,
                    bearer(token, null), new org.springframework.core.ParameterizedTypeReference<>() {
                    });
            ResponseEntity<AccountPage> accounts = get("/api/v1/admin/accounts?role=" + code, token,
                    AccountPage.class);

            assertThat(staff.roleName()).isEqualTo("Điều phối nội dung");
            assertThat(users.getBody()).filteredOn(row -> row.id().equals(staff.id()))
                    .singleElement().satisfies(row -> {
                        assertThat(row.role()).isEqualTo(code);
                        assertThat(row.roleName()).isEqualTo("Điều phối nội dung");
                    });
            assertThat(users.getBody()).filteredOn(row -> row.id().equals(admin.id()))
                    .singleElement().satisfies(row -> assertThat(row.roleName()).isEqualTo("Super admin"));
            assertThat(accounts.getBody().items()).singleElement().satisfies(row -> {
                assertThat(row.id()).isEqualTo(staff.id());
                assertThat(row.roleName()).isEqualTo("Điều phối nội dung");
            });
        } finally {
            dropCustomRole(code);
        }
    }

    @Test
    void aCustomRoleIsListedInAdminAccountsLikeAStaffRole() {
        String code = seedCustomRole("IT_LISTED_ROLE", "Listed role", Permission.SUPPORT_TICKET_VIEW);
        try {
            UserView admin = seedAdmin("admin-listed");
            UserView staff = userService.createUser(admin.id(), uniqueEmail("listed"), PASSWORD, "Listed", code);

            ResponseEntity<AccountPage> all = get("/api/v1/admin/accounts?size=100",
                    login(admin.email(), PASSWORD), AccountPage.class);

            assertThat(all.getBody().items()).extracting(AccountRow::id).contains(staff.id());
        } finally {
            dropCustomRole(code);
        }
    }

    private static HttpEntity<Object> bearer(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    record RoleBody(String role) {
    }

    record MeKeysView(List<String> permissions) {
    }

    record UserRow(UUID id, String role, String roleName) {
    }

    record AccountRow(UUID id, String role, String roleName, Instant createdAt) {
    }

    record AccountPage(List<AccountRow> items) {
    }
}
