package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The system keeps one active super admin even when removals race. The database is shared with every other
 * integration test, so each test first parks the other active super admins (restored afterwards) and then
 * holds the rows of the accounts under test locked until both requests are waiting on them: both requests
 * have then passed their own authorization and meet at the invariant's row locks.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SuperAdminInvariantIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String ACTIVE_SUPER_ADMIN =
            "role = 'ADMIN' AND status = 'ACTIVE' AND admin_disabled_at IS NULL";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private DataSource dataSource;

    private List<UUID> parked = List.of();

    @BeforeEach
    void parkTheOtherSuperAdmins() {
        parked = jdbc.queryForList("SELECT id FROM app_user WHERE " + ACTIVE_SUPER_ADMIN, UUID.class);
        jdbc.update("UPDATE app_user SET admin_disabled_at = now() WHERE " + ACTIVE_SUPER_ADMIN);
    }

    @AfterEach
    void restoreTheParkedSuperAdmins() {
        if (!parked.isEmpty()) {
            namedJdbc.update("UPDATE app_user SET admin_disabled_at = NULL, admin_disabled_by = NULL"
                    + " WHERE id IN (:ids)", new MapSqlParameterSource("ids", parked));
        }
    }

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private UserView seedUser(UserView admin, String label, String role) {
        return userService.createUser(admin.id(), uniqueEmail(label), PASSWORD, label, role);
    }

    private ResponseEntity<String> patch(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private int activeSuperAdminsAmong(UserView... accounts) {
        int active = 0;
        for (UserView account : accounts) {
            active += jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ? AND " + ACTIVE_SUPER_ADMIN,
                    Integer.class, account.id());
        }
        return active;
    }

    /**
     * Holds {@code accounts} row-locked, starts both requests, waits until both wait on a lock, then
     * releases the rows so the two requests contend for the invariant's locks.
     */
    private List<ResponseEntity<String>> runAtOnce(List<UserView> accounts,
            List<Callable<ResponseEntity<String>>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM app_user WHERE id = ANY (?) ORDER BY id FOR UPDATE")) {
                lock.setArray(1, holder.createArrayOf("uuid", accounts.stream().map(UserView::id).toArray()));
                lock.executeQuery();
            }
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<String>> request : requests) {
                futures.add(pool.submit(request));
            }
            awaitWaitingOnLocks(requests.size());
            holder.commit();
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitWaitingOnLocks(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0",
                    Integer.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The " + expected + " requests never waited on the row locks");
    }

    private static void assertOneSucceededAndOneWasRefusedAsLast(List<ResponseEntity<String>> responses) {
        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        ResponseEntity<String> refused = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow();
        assertThat(refused.getBody()).contains("last active super admin");
    }

    @Test
    void twoSuperAdminsDeactivatingEachOtherAtOnceLeaveOne() throws Exception {
        UserView an = seedAdmin("an-deactivate");
        UserView binh = seedAdmin("binh-deactivate");
        String anToken = login(an.email(), PASSWORD);
        String binhToken = login(binh.email(), PASSWORD);

        List<ResponseEntity<String>> responses = runAtOnce(List.of(an, binh), List.of(
                () -> post("/api/v1/admin/accounts/" + binh.id() + "/deactivate", anToken, null, String.class),
                () -> post("/api/v1/admin/accounts/" + an.id() + "/deactivate", binhToken, null, String.class)));

        assertOneSucceededAndOneWasRefusedAsLast(responses);
        assertThat(activeSuperAdminsAmong(an, binh)).isEqualTo(1);
    }

    @Test
    void aSuspensionAndARoleChangeAtOnceLeaveOne() throws Exception {
        UserView an = seedAdmin("an-two-routes");
        UserView binh = seedUser(an, "binh-two-routes", "ADMIN");
        String anToken = login(an.email(), PASSWORD);
        String binhToken = login(binh.email(), PASSWORD);

        List<ResponseEntity<String>> responses = runAtOnce(List.of(an, binh), List.of(
                () -> put("/api/v1/users/" + binh.id(), anToken,
                        new UpdateUserRequestView(null, null, null, "SUSPENDED"), String.class),
                () -> patch("/api/v1/admin/accounts/" + an.id() + "/role", binhToken,
                        new RoleRequestView("OPERATION_MANAGER"))));

        assertOneSucceededAndOneWasRefusedAsLast(responses);
        assertThat(activeSuperAdminsAmong(an, binh)).isEqualTo(1);
    }

    /**
     * Neither super admin can reach the other through {@code /users} (each acts in its own scope), so a
     * delegated staff caller holding the admin-account key, in the scope of both, sends the two updates.
     */
    @Test
    void twoUpdatesEachSendingRoleAndStatusAtOnceLeaveOne() throws Exception {
        UserView an = seedAdmin("an-role-and-status");
        UserView binh = seedUser(an, "binh-role-and-status", "ADMIN");
        UserView manager = seedUser(an, "manager-role-and-status", "OPERATION_MANAGER");
        String managerToken = login(manager.email(), PASSWORD);
        jdbc.update("INSERT INTO role_permission (role_code, permission_key)"
                + " VALUES ('OPERATION_MANAGER', 'system.admin_account.manage') ON CONFLICT DO NOTHING");
        try {
            List<ResponseEntity<String>> responses = runAtOnce(List.of(an, binh), List.of(
                    () -> put("/api/v1/users/" + an.id(), managerToken,
                            new UpdateUserRequestView(null, null, "MEMBER", "SUSPENDED"), String.class),
                    () -> put("/api/v1/users/" + binh.id(), managerToken,
                            new UpdateUserRequestView(null, null, "MEMBER", "SUSPENDED"), String.class)));

            assertOneSucceededAndOneWasRefusedAsLast(responses);
            assertThat(activeSuperAdminsAmong(an, binh)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM role_permission WHERE role_code = 'OPERATION_MANAGER'"
                    + " AND permission_key = 'system.admin_account.manage'");
        }
    }

    @Test
    void removingANonLastSuperAdminSucceeds() {
        UserView an = seedAdmin("an-not-last");
        UserView binh = seedUser(an, "binh-not-last", "ADMIN");
        seedUser(an, "chi-not-last", "ADMIN");
        String anToken = login(an.email(), PASSWORD);

        ResponseEntity<String> response = put("/api/v1/users/" + binh.id(), anToken,
                new UpdateUserRequestView(null, null, null, "SUSPENDED"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activeSuperAdminsAmong(an)).isEqualTo(1);
    }

    record RoleRequestView(String role) {
    }
}
