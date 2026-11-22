package com.flowcortex.app.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowView;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
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

/** The first-result claim against a real database: once per account, the earliest completed run, one winner per race. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@SuppressWarnings({"rawtypes", "unchecked"})
class FirstResultClaimIT extends AbstractPostgresBootTest {

    private static final String CLAIM = "/api/v1/me/first-result/claim";

    /** The backfill statement of the first_result_report migration. */
    private static final String BACKFILL = """
            INSERT INTO first_result_report (user_id, run_id, workflow_id, source, result_at, reported_at)
            SELECT DISTINCT ON (r.owner_id) r.owner_id, r.id, r.workflow_id, 'backfill', COALESCE(r.finished_at, r.started_at), now()
              FROM workflow_run r
             WHERE r.status = 'completed'
             ORDER BY r.owner_id, r.started_at, r.id
            ON CONFLICT (user_id) DO NOTHING
            """;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    private record Member(UUID id, String token) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void claimReturnsTheEarliestCompletedRunOnce() {
        Member ana = member("ana");
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        UUID workflowId = workflow(ana);
        seedRun(ana, workflowId, "completed", base.plusSeconds(60), base.plusSeconds(90));
        UUID earliest = seedRun(ana, workflowId, "completed", base, base.plusSeconds(30));
        seedRun(ana, workflowId, "error", base.minusSeconds(60), base.minusSeconds(30));

        ResponseEntity<Map> first = claim(ana.token());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("runId", earliest.toString())
                .containsEntry("workflowId", workflowId.toString());
        assertThat(count(ana)).isEqualTo(1);
    }

    @Test
    void secondClaimIs204() {
        Member ana = member("ana");
        seedRun(ana, workflow(ana), "completed", Instant.now(), Instant.now());
        assertThat(claim(ana.token()).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> again = claim(ana.token());

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count(ana)).isEqualTo(1);
    }

    @Test
    void noCompletedRunIs204() {
        Member ana = member("ana");
        seedRun(ana, workflow(ana), "error", Instant.now(), Instant.now());

        assertThat(claim(ana.token()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count(ana)).isZero();
    }

    @Test
    void twoConcurrentClaimsGiveOne200AndOne204() throws Exception {
        Member ana = member("ana");
        seedRun(ana, workflow(ana), "completed", Instant.now(), Instant.now());

        List<ResponseEntity<Map>> both = concurrently(() -> claim(ana.token()), () -> claim(ana.token()));

        assertThat(both.stream().map(ResponseEntity::getStatusCode))
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.NO_CONTENT);
        assertThat(count(ana)).isEqualTo(1);
    }

    @Test
    void reportKeepsTheRunFinishTime() {
        Member ana = member("ana");
        Instant started = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant finished = started.plusSeconds(45);
        seedRun(ana, workflow(ana), "completed", started, finished);
        Instant before = Instant.now().minusSeconds(1);

        claim(ana.token());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source, result_at, reported_at FROM first_result_report WHERE user_id = ?", ana.id());
        assertThat(row.get("source")).isEqualTo("claim");
        assertThat(((Timestamp) row.get("result_at")).toInstant()).isEqualTo(finished);
        assertThat(((Timestamp) row.get("reported_at")).toInstant()).isAfter(before);
    }

    @Test
    void anotherMembersRunDoesNotCount() {
        Member ana = member("ana");
        Member binh = member("binh");
        seedRun(binh, workflow(binh), "completed", Instant.now(), Instant.now());

        assertThat(claim(ana.token()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count(ana)).isZero();
    }

    @Test
    void anonymousClaimIs401() {
        assertThat(claim(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void backfillStatementAddsNothingWhenReRun() {
        Member ana = member("ana");
        seedRun(ana, workflow(ana), "completed", Instant.now(), Instant.now());
        assertThat(claim(ana.token()).getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbc.update(BACKFILL);
        long afterFirst = jdbc.queryForObject("SELECT count(*) FROM first_result_report", Long.class);
        jdbc.update(BACKFILL);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM first_result_report", Long.class)).isEqualTo(afterFirst);
        assertThat(jdbc.queryForObject("SELECT source FROM first_result_report WHERE user_id = ?", String.class,
                ana.id())).isEqualTo("claim");
    }

    private ResponseEntity<Map> claim(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(CLAIM, HttpMethod.POST, new HttpEntity<>(null, headers), Map.class);
    }

    private Member member(String prefix) {
        UserView user = userService.createInitialAdmin(prefix + "-" + UUID.randomUUID() + "@example.com",
                "password123", prefix);
        ResponseEntity<TokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(user.email(), "password123"), TokenResponse.class);
        return new Member(user.id(), login.getBody().accessToken());
    }

    private UUID workflow(Member owner) {
        WorkflowView workflow = workflowService.createWorkflow(owner.id(), "claim-" + UUID.randomUUID(), false,
                List.of(new NewWorkflowStep("Echo", "echo", null)));
        return workflow.id();
    }

    private UUID seedRun(Member owner, UUID workflowId, String status, Instant startedAt, Instant finishedAt) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                + "VALUES (?, ?, ?, ?, 'x', ?, ?)", runId, workflowId, owner.id(), status,
                Timestamp.from(startedAt), Timestamp.from(finishedAt));
        return runId;
    }

    private long count(Member member) {
        return jdbc.queryForObject("SELECT count(*) FROM first_result_report WHERE user_id = ?", Long.class,
                member.id());
    }

    /** Runs the calls on separate threads released together. */
    @SafeVarargs
    private static List<ResponseEntity<Map>> concurrently(Callable<ResponseEntity<Map>>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<Map>> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<ResponseEntity<Map>> results = new ArrayList<>();
            for (Future<ResponseEntity<Map>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
