package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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

/**
 * A deactivation or role change must survive a concurrent write by the target itself: both requests
 * are held on the target's row lock until both are waiting, in submission order, then released together.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminAccountWriteRaceIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final int ROUNDS = 5;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void aDeactivationSurvivesTheTargetUpdatingItsOwnProfileAtOnce() throws Exception {
        UserView admin = userService.createInitialAdmin(uniqueEmail("race-admin"), PASSWORD, "Race Admin");
        String adminToken = login(admin.email(), PASSWORD);
        for (int round = 0; round < ROUNDS; round++) {
            UserView staff = userService.createUser(admin.id(), uniqueEmail("race-staff"), PASSWORD, "Staff",
                    "CUSTOMER_SUPPORT");
            String staffToken = login(staff.email(), PASSWORD);

            List<ResponseEntity<String>> responses = runAtOnce(staff, List.of(
                    () -> post("/api/v1/admin/accounts/" + staff.id() + "/deactivate", adminToken, null,
                            String.class),
                    () -> put("/api/v1/auth/profile", staffToken, new ProfileRequest("Renamed"), String.class)));

            assertThat(responses.get(0).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(jdbc.queryForObject("SELECT admin_disabled_at IS NOT NULL AND admin_disabled_by = ?"
                    + " FROM app_user WHERE id = ?", Boolean.class, admin.id(), staff.id())).isTrue();
        }
    }

    @Test
    void aRoleChangeAndADeactivationAtOnceBothLand() throws Exception {
        UserView admin = userService.createInitialAdmin(uniqueEmail("race-role-admin"), PASSWORD, "Race Admin");
        String adminToken = login(admin.email(), PASSWORD);
        for (int round = 0; round < ROUNDS; round++) {
            UserView staff = userService.createUser(admin.id(), uniqueEmail("race-role-staff"), PASSWORD, "Staff",
                    "OPERATION_MANAGER");

            runAtOnce(staff, List.of(
                    () -> post("/api/v1/admin/accounts/" + staff.id() + "/deactivate", adminToken, null,
                            String.class),
                    () -> patch("/api/v1/admin/accounts/" + staff.id() + "/role", adminToken,
                            new RoleRequest("CUSTOMER_SUPPORT"))));

            assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, staff.id()))
                    .isEqualTo("CUSTOMER_SUPPORT");
            assertThat(jdbc.queryForObject("SELECT admin_disabled_at IS NOT NULL FROM app_user WHERE id = ?",
                    Boolean.class, staff.id())).isTrue();
        }
    }

    private ResponseEntity<String> patch(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private List<ResponseEntity<String>> runAtOnce(UserView target,
            List<Callable<ResponseEntity<String>>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM app_user WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, target.id());
                lock.executeQuery();
            }
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<String>> request : requests) {
                futures.add(pool.submit(request));
                awaitWaitingOnLocks(futures.size());
            }
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
        throw new AssertionError("The " + expected + " requests never waited on the row lock");
    }

    record ProfileRequest(String name) {
    }

    record RoleRequest(String role) {
    }
}
