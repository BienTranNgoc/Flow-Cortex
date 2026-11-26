package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A member reports a workflow over real HTTP and the real database: what is stored, what a repeat leaves alone, who may
 * not report, and the hourly bound, including two requests racing for the last slot.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(WorkflowReportEndpointsIT.ShiftedClockConfig.class)
class WorkflowReportEndpointsIT extends AbstractWorkflowHttpIT {

    private static final String WORKFLOWS = "/api/v1/workflows/";

    private final List<UUID> touched = new ArrayList<>();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ShiftedClock clock;

    @Test
    void firstReportIsStoredPending() {
        UserView owner = newUser("report-first-owner");
        UserView reporter = member(owner, "report-first");
        UUID id = publicWorkflow(owner);

        ResponseEntity<String> response = report(loginAs(reporter), id, "spam", "  looks like an ad  ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"reported\":true");
        Map<String, Object> row = reportRow(id, reporter);
        assertThat(row).containsEntry("status", "pending").containsEntry("reason", "spam")
                .containsEntry("details", "looks like an ad").containsEntry("resolution", null)
                .containsEntry("reviewer_id", null);
    }

    @Test
    void repeatStoresNothingAndKeepsTheFirstReason() {
        UserView owner = newUser("report-repeat-owner");
        UserView reporter = member(owner, "report-repeat");
        String token = loginAs(reporter);
        UUID id = publicWorkflow(owner);
        assertThat(report(token, id, "spam", "first").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> again = report(token, id, "scam", "second");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).contains("\"reported\":true");
        assertThat(reportCount(id)).isEqualTo(1);
        assertThat(reportRow(id, reporter)).containsEntry("reason", "spam").containsEntry("details", "first");
    }

    @Test
    void reporterIdInBodyIsIgnored() {
        UserView owner = newUser("report-body-owner");
        UserView reporter = member(owner, "report-body");
        UserView other = member(owner, "report-body-other");
        UUID id = publicWorkflow(owner);

        ResponseEntity<String> response = call(HttpMethod.POST, WORKFLOWS + id + "/report", loginAs(reporter),
                Map.of("reason", "spam", "reporterId", other.id().toString()), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForList("SELECT reporter_id FROM workflow_report WHERE workflow_id = ?", UUID.class, id))
                .containsExactly(reporter.id());
    }

    @Test
    void unknownReasonIs422AndStoresNothing() {
        UserView owner = newUser("report-reason-owner");
        String token = loginAs(member(owner, "report-reason"));
        UUID id = publicWorkflow(owner);

        for (String reason : new String[] {"nonsense", "", "  "}) {
            ResponseEntity<String> response = report(token, id, reason, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.getBody()).contains("\"reason\"");
        }
        ResponseEntity<String> missing = call(HttpMethod.POST, WORKFLOWS + id + "/report", token, Map.of(), null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<String> tooLong = report(token, id, "spam", "x".repeat(1001));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(tooLong.getBody()).contains("\"details\"");
        assertThat(reportCount(id)).isZero();
    }

    @Test
    void privateWorkflowOfAnotherIs404() {
        UserView owner = newUser("report-private-owner");
        String token = loginAs(member(owner, "report-private"));
        UUID id = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();

        assertThat(report(token, id, "spam", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reportCount(id)).isZero();
    }

    @Test
    void hiddenWorkflowIs404() {
        UserView owner = newUser("report-hidden-owner");
        String token = loginAs(member(owner, "report-hidden"));
        UUID id = publicWorkflow(owner);
        jdbc.update("UPDATE workflow SET moderation_status = 'hidden' WHERE id = ?", id);

        assertThat(report(token, id, "spam", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reportCount(id)).isZero();
    }

    @Test
    void unlistedWithGrantIsReportable() {
        UserView owner = newUser("report-unlisted-owner");
        UserView reporter = member(owner, "report-unlisted");
        String token = loginAs(reporter);
        UUID id = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        ResponseEntity<Map> visibility = call(HttpMethod.PATCH, WORKFLOWS + id + "/visibility", loginAs(owner),
                Map.of("visibility", "unlisted"), Map.class);
        assertThat(visibility.getStatusCode()).isEqualTo(HttpStatus.OK);
        String key = jdbc.queryForObject("SELECT unlisted_key FROM workflow WHERE id = ?", String.class, id);
        assertThat(report(token, id, "spam", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(call(HttpMethod.GET, WORKFLOWS + id + "?key=" + key, token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(report(token, id, "spam", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reportCount(id)).isEqualTo(1);
    }

    @Test
    void ownWorkflowIs403() {
        UserView owner = newUser("report-own");
        UUID id = publicWorkflow(owner);

        assertThat(report(loginAs(owner), id, "spam", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reportCount(id)).isZero();
    }

    @Test
    void sixthReportInTheHourIs429() {
        UserView owner = newUser("report-sixth-owner");
        UserView reporter = member(owner, "report-sixth");
        String token = loginAs(reporter);
        List<UUID> ids = fileFive(owner, token);
        UUID sixth = publicWorkflow(owner);

        ResponseEntity<String> response = report(token, sixth, "spam", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(reportCount(sixth)).isZero();
        assertThat(ids).allSatisfy(id -> assertThat(reportCount(id)).isEqualTo(1));
    }

    @Test
    void reportAfterTheWindowRollsIsStored() {
        UserView owner = newUser("report-roll-owner");
        UserView reporter = member(owner, "report-roll");
        String token = loginAs(reporter);
        fileFive(owner, token);
        UUID sixth = publicWorkflow(owner);
        assertThat(report(token, sixth, "spam", null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        clock.advance(Duration.ofMinutes(61));

        assertThat(report(token, sixth, "spam", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reportCount(sixth)).isEqualTo(1);
    }

    @Test
    void repeatAtTheBoundIs200() {
        UserView owner = newUser("report-bound-owner");
        UserView reporter = member(owner, "report-bound");
        String token = loginAs(reporter);
        List<UUID> ids = fileFive(owner, token);

        ResponseEntity<String> response = report(token, ids.getFirst(), "scam", "again");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_report WHERE reporter_id = ?", Long.class,
                reporter.id())).isEqualTo(5);
    }

    @Test
    void twoConcurrentReportsAtTheBoundStoreOne() throws Exception {
        UserView owner = newUser("report-race-owner");
        UserView reporter = member(owner, "report-race");
        String token = loginAs(reporter);
        for (int i = 0; i < 4; i++) {
            UUID id = publicWorkflow(owner);
            assertThat(report(token, id, "spam", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        UUID first = publicWorkflow(owner);
        UUID second = publicWorkflow(owner);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpStatus>> results = new ArrayList<>();
            for (UUID id : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return HttpStatus.valueOf(report(token, id, "spam", null).getStatusCode().value());
                }));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.TOO_MANY_REQUESTS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_report WHERE reporter_id = ?", Long.class,
                reporter.id())).isEqualTo(5);
    }

    @Test
    void refusalDetailIsVietnameseForAVietnameseCaller() {
        UserView owner = newUser("report-vi");
        UUID id = publicWorkflow(owner);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loginAs(owner));
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");

        ResponseEntity<String> response = restTemplate.exchange(WORKFLOWS + id + "/report", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "spam"), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("Bạn không thể báo cáo quy trình của chính mình");
    }

    @AfterEach
    void cleanUp() {
        clock.reset();
        for (UUID id : touched) {
            jdbc.update("DELETE FROM workflow_report WHERE workflow_id = ?", id);
            jdbc.update("UPDATE workflow SET moderation_status = 'visible' WHERE id = ?", id);
        }
        touched.clear();
    }

    private List<UUID> fileFive(UserView owner, String token) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = publicWorkflow(owner);
            assertThat(report(token, id, "spam", null).getStatusCode()).isEqualTo(HttpStatus.OK);
            ids.add(id);
        }
        return ids;
    }

    /** Creates a public workflow and remembers it, so its reports and any moderation state are cleaned afterwards. */
    private UUID publicWorkflow(UserView owner) {
        UUID id = newWorkflow(owner.id(), true, new NewWorkflowStep("only", "echo", null)).id();
        touched.add(id);
        return id;
    }

    private long reportCount(UUID workflowId) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_report WHERE workflow_id = ?", Long.class,
                workflowId);
    }

    private Map<String, Object> reportRow(UUID workflowId, UserView reporter) {
        return jdbc.queryForMap("SELECT status, reason, details, resolution, reviewer_id FROM workflow_report "
                + "WHERE workflow_id = ? AND reporter_id = ?", workflowId, reporter.id());
    }

    private ResponseEntity<String> report(String token, UUID workflowId, String reason, String details) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("reason", reason);
        body.put("details", details);
        return call(HttpMethod.POST, WORKFLOWS + workflowId + "/report", token, body, null);
    }

    private UserView member(UserView admin, String prefix) {
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", prefix, "MEMBER");
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        @SuppressWarnings("unchecked")
        Class<T> responseType = type != null ? type : (Class<T>) String.class;
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), responseType);
    }

    /** System time plus an offset a test can move; every reader of the app's Clock sees it. */
    static class ShiftedClock extends Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        void advance(Duration by) {
            offset.updateAndGet(current -> current.plus(by));
        }

        void reset() {
            offset.set(Duration.ZERO);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed to UTC");
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ShiftedClockConfig {

        @Bean
        @Primary
        ShiftedClock shiftedClock() {
            return new ShiftedClock();
        }
    }
}
