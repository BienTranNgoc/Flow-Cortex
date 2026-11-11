package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The role API: a super admin creates, renames, deletes and edits the keys of custom roles over HTTP. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminRolesEndpointsIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String ROLES = "/api/v1/admin/roles";
    private static final List<String> TICKET_VIEW = List.of("support.ticket.view");
    private static final List<String> TICKET_VIEW_AND_REPLY = List.of("support.ticket.view", "support.ticket.reply");

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void dropCustomRoles() {
        jdbc.update("DELETE FROM app_user WHERE role IN (SELECT code FROM role WHERE NOT system)");
        jdbc.update("DELETE FROM role WHERE NOT system");
    }

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private String adminToken(String label) {
        return login(seedAdmin(label).email(), PASSWORD);
    }

    private ResponseEntity<RoleView> createRole(String token, String name, List<String> keys) {
        return post(ROLES, token, new CreateBody(name, null, keys), RoleView.class);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private int keyCount(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM role_permission WHERE role_code = ?", Integer.class, code);
    }

    private List<String> storedKeys(String code) {
        return jdbc.queryForList(
                "SELECT permission_key FROM role_permission WHERE role_code = ? ORDER BY permission_key",
                String.class, code);
    }

    private <T> List<T> inParallel(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void createsACustomRoleWithItsKeys() {
        String token = adminToken("admin-create-role");

        ResponseEntity<RoleView> created = post(ROLES, token,
                new CreateBody("Điều phối nội dung", "  Reviews copy  ", TICKET_VIEW_AND_REPLY), RoleView.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasPath(ROLES + "/DIEU_PHOI_NOI_DUNG");
        RoleView role = created.getBody();
        assertThat(role.code()).isEqualTo("DIEU_PHOI_NOI_DUNG");
        assertThat(role.name()).isEqualTo("Điều phối nội dung");
        assertThat(role.description()).isEqualTo("Reviews copy");
        assertThat(role.system()).isFalse();
        assertThat(role.staff()).isTrue();
        assertThat(role.superAdmin()).isFalse();
        assertThat(role.permissions()).containsExactly("support.ticket.reply", "support.ticket.view");
        assertThat(role.permissionsVersion()).isZero();
        assertThat(role.accountCount()).isZero();
        assertThat(get(ROLES + "/DIEU_PHOI_NOI_DUNG", token, RoleView.class).getBody()).isEqualTo(role);
    }

    @Test
    void anInvalidInitialSetIsUnprocessableAndCreatesNothing() {
        String token = adminToken("admin-invalid-set");

        ResponseEntity<String> unknown = post(ROLES, token,
                new CreateBody("Bad one", null, List.of("system.role.manage", "nope.nope.nope")), String.class);
        ResponseEntity<String> superAdminOnly = post(ROLES, token,
                new CreateBody("Bad two", null, List.of("system.role.manage")), String.class);
        ResponseEntity<String> missingView = post(ROLES, token,
                new CreateBody("Bad three", null, List.of("support.ticket.reply")), String.class);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(unknown.getBody()).contains("PERMISSION_UNKNOWN").contains("nope.nope.nope");
        assertThat(superAdminOnly.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(superAdminOnly.getBody()).contains("PERMISSION_SUPER_ADMIN_ONLY");
        assertThat(missingView.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(missingView.getBody()).contains("PERMISSION_SET_INCONSISTENT").contains("support.ticket");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role WHERE NOT system", Integer.class)).isZero();
    }

    @Test
    void aBlankOrOversizedNameIsBadRequestAndNewGetsASuffixedCode() {
        String token = adminToken("admin-name-rules");

        ResponseEntity<String> blank = post(ROLES, token, new CreateBody("   ", null, List.of()), String.class);
        ResponseEntity<String> tooLong = post(ROLES, token, new CreateBody("x".repeat(101), null, List.of()),
                String.class);
        ResponseEntity<RoleView> reserved = createRole(token, "New", List.of());

        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserved.getBody().code()).isEqualTo("NEW_2");
    }

    @Test
    void aDuplicateNameIsConflict() {
        String token = adminToken("admin-duplicate-name");
        createRole(token, "Customer escalations", List.of());

        ResponseEntity<String> sameName = post(ROLES, token,
                new CreateBody("  customer ESCALATIONS ", null, List.of()), String.class);
        ResponseEntity<String> systemName = post(ROLES, token, new CreateBody("customer support", null, List.of()),
                String.class);

        assertThat(sameName.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sameName.getBody()).contains("already exists");
        assertThat(systemName.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void twoCreatesWithOneNameAtOnceLeaveOne() throws Exception {
        String token = adminToken("admin-same-name-race");

        List<ResponseEntity<String>> results = inParallel(List.of(
                () -> post(ROLES, token, new CreateBody("Kiểm duyệt viên", null, List.of()), String.class),
                () -> post(ROLES, token, new CreateBody("Kiểm duyệt viên", null, List.of()), String.class)));

        assertThat(results).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(201, 409);
        assertThat(results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().get()
                .getBody()).contains("already exists");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role WHERE NOT system", Integer.class)).isOne();
    }

    @Test
    void twoNamesSluggingAlikeAtOnceGetSuffixedCodes() throws Exception {
        String token = adminToken("admin-alike-race");

        List<ResponseEntity<RoleView>> results = inParallel(List.of(
                () -> createRole(token, "Kiểm duyệt", List.of()),
                () -> createRole(token, "KIEM-DUYET", List.of())));

        assertThat(results).extracting(r -> r.getStatusCode().value()).containsExactly(201, 201);
        assertThat(results).extracting(r -> r.getBody().code()).containsExactlyInAnyOrder("KIEM_DUYET",
                "KIEM_DUYET_2");
    }

    @Test
    void renamingKeepsTheCode() {
        String token = adminToken("admin-rename");
        createRole(token, "Support level two", TICKET_VIEW);

        ResponseEntity<RoleView> renamed = restTemplate.exchange(ROLES + "/SUPPORT_LEVEL_TWO", HttpMethod.PATCH,
                bearer(token, Map.of("name", "Hỗ trợ cấp 2", "description", "Escalations")), RoleView.class);
        ResponseEntity<RoleView> descriptionOnly = restTemplate.exchange(ROLES + "/SUPPORT_LEVEL_TWO",
                HttpMethod.PATCH, bearer(token, Map.of("description", "")), RoleView.class);
        ResponseEntity<String> blank = exchange(HttpMethod.PATCH, ROLES + "/SUPPORT_LEVEL_TWO", token,
                Map.of("name", "  "));
        ResponseEntity<String> taken = exchange(HttpMethod.PATCH, ROLES + "/SUPPORT_LEVEL_TWO", token,
                Map.of("name", "Customer support"));
        ResponseEntity<String> missing = exchange(HttpMethod.PATCH, ROLES + "/NO_SUCH_ROLE", token,
                Map.of("name", "Whatever"));

        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().code()).isEqualTo("SUPPORT_LEVEL_TWO");
        assertThat(renamed.getBody().name()).isEqualTo("Hỗ trợ cấp 2");
        assertThat(renamed.getBody().description()).isEqualTo("Escalations");
        assertThat(descriptionOnly.getBody().name()).isEqualTo("Hỗ trợ cấp 2");
        assertThat(descriptionOnly.getBody().description()).isNull();
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aSystemRoleCannotBeRenamedOrDeleted() {
        String token = adminToken("admin-system-role");

        for (String code : List.of("CUSTOMER_SUPPORT", "ADMIN", "MEMBER")) {
            assertThat(exchange(HttpMethod.PATCH, ROLES + "/" + code, token, Map.of("name", "Renamed"))
                    .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exchange(HttpMethod.DELETE, ROLES + "/" + code, token, null).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
        assertThat(jdbc.queryForObject("SELECT name FROM role WHERE code = 'CUSTOMER_SUPPORT'", String.class))
                .isEqualTo("Customer support");
    }

    @Test
    void aHeldRoleCannotBeDeleted() {
        UserView admin = seedAdmin("admin-held-role");
        String token = login(admin.email(), PASSWORD);
        createRole(token, "Held role", TICKET_VIEW);
        userService.createUser(admin.id(), uniqueEmail("holder"), PASSWORD, "Holder", "HELD_ROLE");

        ResponseEntity<String> refused = exchange(HttpMethod.DELETE, ROLES + "/HELD_ROLE", token, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("1 account");
        assertThat(keyCount("HELD_ROLE")).isOne();
        assertThat(get(ROLES + "/HELD_ROLE", token, RoleView.class).getBody().accountCount()).isOne();
    }

    @Test
    void aDeleteThatLosesToAnAssignmentIsConflictWithTheCount() throws Exception {
        String token = adminToken("admin-delete-race");
        createRole(token, "Raced role", TICKET_VIEW);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection assigner = jdbc.getDataSource().getConnection()) {
            assigner.setAutoCommit(false);
            insertHolder(assigner, "RACED_ROLE");
            Future<ResponseEntity<String>> delete = pool.submit(
                    () -> exchange(HttpMethod.DELETE, ROLES + "/RACED_ROLE", token, null));
            awaitDeleteBlockedOnTheRoleRow();

            assigner.commit();
            ResponseEntity<String> refused = delete.get(30, TimeUnit.SECONDS);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody()).contains("1 account");
        } finally {
            pool.shutdownNow();
        }
        assertThat(keyCount("RACED_ROLE")).isOne();
        assertThat(get(ROLES + "/RACED_ROLE", token, RoleView.class).getBody().accountCount()).isOne();
    }

    private void insertHolder(Connection connection, String role) throws SQLException {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO app_user (id, owner_id, email, password_hash, name, role, status, created_at, "
                        + "updated_at) VALUES (?, ?, ?, 'x', 'Racer', ?, 'ACTIVE', ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, id);
            insert.setString(3, uniqueEmail("racer"));
            insert.setString(4, role);
            insert.setTimestamp(5, now);
            insert.setTimestamp(6, now);
            insert.executeUpdate();
        }
    }

    private void awaitDeleteBlockedOnTheRoleRow() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND query LIKE 'DELETE FROM role WHERE%'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the DELETE never waited on the role row");
    }

    @Test
    void anUnusedCustomRoleIsDeletedWithItsKeys() {
        String token = adminToken("admin-delete-role");
        createRole(token, "Short lived", TICKET_VIEW_AND_REPLY);

        ResponseEntity<String> deleted = exchange(HttpMethod.DELETE, ROLES + "/SHORT_LIVED", token, null);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(keyCount("SHORT_LIVED")).isZero();
        assertThat(get(ROLES + "/SHORT_LIVED", token, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.DELETE, ROLES + "/SHORT_LIVED", token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aMatrixSaveReplacesTheSetAndMovesTheVersion() {
        String token = adminToken("admin-matrix-save");
        createRole(token, "Matrix role", TICKET_VIEW);

        ResponseEntity<RoleView> saved = put(ROLES + "/MATRIX_ROLE/permissions", token,
                new ReplaceBody(List.of("support.ticket.view", "support.ticket.close", "workflow.workflow.manage",
                        "support.ticket.view"), 0), RoleView.class);
        ResponseEntity<String> stale = put(ROLES + "/MATRIX_ROLE/permissions", token,
                new ReplaceBody(TICKET_VIEW, 0), String.class);
        ResponseEntity<String> missing = put(ROLES + "/NO_SUCH_ROLE/permissions", token,
                new ReplaceBody(TICKET_VIEW, 0), String.class);
        ResponseEntity<String> unknownKey = put(ROLES + "/MATRIX_ROLE/permissions", token,
                new ReplaceBody(List.of("nope.nope.nope"), 1), String.class);
        ResponseEntity<String> negativeVersion = put(ROLES + "/MATRIX_ROLE/permissions", token,
                new ReplaceBody(TICKET_VIEW, -1), String.class);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().permissionsVersion()).isOne();
        assertThat(saved.getBody().permissions()).containsExactly("support.ticket.close", "support.ticket.view",
                "workflow.workflow.manage");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknownKey.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(negativeVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(storedKeys("MATRIX_ROLE")).containsExactly("support.ticket.close", "support.ticket.view",
                "workflow.workflow.manage");
    }

    @Test
    void twoMatrixSavesAtOnceKeepTheWinner() throws Exception {
        String token = adminToken("admin-matrix-race");
        createRole(token, "Race role", TICKET_VIEW);
        List<String> first = List.of("support.ticket.view", "support.ticket.reply");
        List<String> second = List.of("support.ticket.view", "support.ticket.close");

        List<ResponseEntity<String>> results = inParallel(List.of(
                () -> put(ROLES + "/RACE_ROLE/permissions", token, new ReplaceBody(first, 0), String.class),
                () -> put(ROLES + "/RACE_ROLE/permissions", token, new ReplaceBody(second, 0), String.class)));

        assertThat(results).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(200, 409);
        List<String> winnerKeys = results.get(0).getStatusCode() == HttpStatus.OK ? first : second;
        assertThat(get(ROLES + "/RACE_ROLE", token, RoleView.class).getBody().permissionsVersion()).isOne();
        assertThat(storedKeys("RACE_ROLE")).containsExactlyElementsOf(winnerKeys.stream().sorted().toList());
    }

    @Test
    void adminAndEndUserRolesAreNotEditable() {
        String token = adminToken("admin-not-editable");

        for (String code : List.of("ADMIN", "MEMBER", "CREATOR")) {
            ResponseEntity<String> refused = put(ROLES + "/" + code + "/permissions", token,
                    new ReplaceBody(List.of(), 0), String.class);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(refused.getBody()).contains("ROLE_NOT_EDITABLE");
        }
    }

    @Test
    void aSystemStaffRoleKeySetIsEditable() {
        String token = adminToken("admin-edit-system-keys");
        int version = get(ROLES + "/CONTENT_EDITOR", token, RoleView.class).getBody().permissionsVersion();
        List<String> original = storedKeys("CONTENT_EDITOR");
        try {
            ResponseEntity<RoleView> saved = put(ROLES + "/CONTENT_EDITOR/permissions", token,
                    new ReplaceBody(List.of("content.help_topic.view"), version), RoleView.class);

            assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(saved.getBody().permissions()).containsExactly("content.help_topic.view");
        } finally {
            jdbc.update("DELETE FROM role_permission WHERE role_code = 'CONTENT_EDITOR'");
            original.forEach(key -> jdbc.update(
                    "INSERT INTO role_permission (role_code, permission_key) VALUES ('CONTENT_EDITOR', ?)", key));
            jdbc.update("UPDATE role SET permissions_version = ? WHERE code = 'CONTENT_EDITOR'", version);
        }
    }

    @Test
    void aSavedSetBitesTheHoldersNextRequest() {
        UserView admin = seedAdmin("admin-bites");
        String adminToken = login(admin.email(), PASSWORD);
        createRole(adminToken, "Bites role", TICKET_VIEW);
        UserView holder = userService.createUser(admin.id(), uniqueEmail("holder"), PASSWORD, "Holder", "BITES_ROLE");
        String holderToken = login(holder.email(), PASSWORD);

        HttpStatus before = HttpStatus.valueOf(
                get("/api/v1/admin/support/requests", holderToken, String.class).getStatusCode().value());
        put(ROLES + "/BITES_ROLE/permissions", adminToken, new ReplaceBody(List.of(), 0), RoleView.class);
        HttpStatus after = HttpStatus.valueOf(
                get("/api/v1/admin/support/requests", holderToken, String.class).getStatusCode().value());

        assertThat(before).isEqualTo(HttpStatus.OK);
        assertThat(after).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theListOrdersSuperAdminSystemStaffCustomByNameThenEndUserRoles() {
        String token = adminToken("admin-role-order");
        createRole(token, "Zeta role", List.of());
        createRole(token, "alpha role", List.of());

        ResponseEntity<List<RoleView>> listed = restTemplate.exchange(ROLES, HttpMethod.GET, bearer(token, null),
                new ParameterizedTypeReference<>() {
                });

        List<String> codes = listed.getBody().stream().map(RoleView::code).toList();
        assertThat(codes).startsWith("ADMIN");
        assertThat(codes.subList(codes.size() - 2, codes.size())).containsExactly("MEMBER", "CREATOR");
        assertThat(codes).containsSubsequence("ADMIN", "CUSTOMER_SUPPORT", "ALPHA_ROLE", "ZETA_ROLE", "MEMBER");
        assertThat(codes.indexOf("PLATFORM_OPERATOR")).isLessThan(codes.indexOf("ALPHA_ROLE"));
        assertThat(listed.getBody().get(0).permissions()).hasSize(
                com.flowcortex.shared.security.authz.Permission.values().length);
    }

    @Test
    void theCatalogRouteServesTheEnumInDeclarationOrder() {
        String token = adminToken("admin-catalog");

        ResponseEntity<List<CatalogView>> catalog = restTemplate.exchange("/api/v1/admin/permission-catalog",
                HttpMethod.GET, bearer(token, null), new ParameterizedTypeReference<>() {
                });

        assertThat(catalog.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalog.getBody()).extracting(CatalogView::key).containsExactly(
                Stream.of(com.flowcortex.shared.security.authz.Permission.values())
                        .map(com.flowcortex.shared.security.authz.Permission::key).toArray(String[]::new));
        assertThat(catalog.getBody()).filteredOn(e -> e.key().equals("system.role.manage")).singleElement()
                .satisfies(e -> {
                    assertThat(e.module()).isEqualTo("system");
                    assertThat(e.feature()).isEqualTo("role");
                    assertThat(e.action()).isEqualTo("manage");
                    assertThat(e.superAdminOnly()).isTrue();
                });
    }

    @Test
    void staffGets403OnEveryRoleRoute() {
        UserView admin = seedAdmin("admin-staff-403");
        UserView staff = userService.createUser(admin.id(), uniqueEmail("staff"), PASSWORD, "Staff",
                "OPERATION_MANAGER");
        String token = login(staff.email(), PASSWORD);

        assertThat(exchange(HttpMethod.GET, ROLES, token, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.GET, "/api/v1/admin/permission-catalog", token, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.POST, ROLES, token, new CreateBody("Nope", null, List.of()))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.PATCH, ROLES + "/CUSTOMER_SUPPORT", token, Map.of("name", "Nope"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.DELETE, ROLES + "/CUSTOMER_SUPPORT", token, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.PUT, ROLES + "/CUSTOMER_SUPPORT/permissions", token,
                new ReplaceBody(List.of(), 0)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role WHERE NOT system", Integer.class)).isZero();
    }

    private static HttpEntity<Object> bearer(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    record CreateBody(String name, String description, List<String> permissionKeys) {
    }

    record ReplaceBody(List<String> keys, int expectedVersion) {
    }

    record RoleView(String code, String name, String description, boolean system, boolean staff,
            boolean superAdmin, List<String> permissions, int permissionsVersion, int accountCount) {
    }

    record CatalogView(String key, String module, String feature, String action, boolean superAdminOnly) {
    }
}
