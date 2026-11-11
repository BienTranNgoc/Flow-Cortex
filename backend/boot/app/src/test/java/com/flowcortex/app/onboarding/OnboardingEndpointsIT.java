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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** The seven onboarding routes against a real database, with the journey switch on. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = "flowcortex.onboarding.enabled=true")
@SuppressWarnings({"rawtypes", "unchecked"})
class OnboardingEndpointsIT extends AbstractPostgresBootTest {

    private static final String BASE = "/api/v1/onboarding";

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
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
    void anonymousCallerIsRefused() {
        assertThat(call(HttpMethod.GET, BASE + "/status", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, BASE + "/progress", null, "{\"step\":\"welcome\"}").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void newMemberStatusIsEmptyAndOpen() {
        Member ana = member("ana");

        Map<String, Object> status = ana.status();

        assertThat(status).containsEntry("completed", false).containsEntry("completedAt", null)
                .containsEntry("purposes", null).containsEntry("source", null).containsEntry("aiLevel", null)
                .containsEntry("industries", null).containsEntry("contentTypes", null)
                .containsEntry("usageType", null).containsEntry("progress", Map.of())
                .containsEntry("firstRunDone", false).containsEntry("open", true).containsEntry("entry", "first")
                .containsEntry("sampleWorkflow", null).containsEntry("firstResult", "none");
        assertThat(rows(ana)).isZero();
    }

    @Test
    void aCompletedRunClosesTheFlowForAMemberWhoNeverStarted() {
        Member ana = member("ana");
        Member binh = member("binh");
        seedRun(ana, "error");
        seedRun(binh, "completed");
        assertThat(ana.status()).containsEntry("firstRunDone", false).containsEntry("open", true);

        seedRun(ana, "completed");

        assertThat(ana.status()).containsEntry("firstRunDone", true).containsEntry("open", false)
                .containsEntry("entry", null);
    }

    @Test
    void progressWriteAfterFinishWithARunIs409AndChangesNothing() {
        Member ana = member("ana");
        assertThat(ana.post("/progress", "{\"finished\":true}").getStatusCode()).isEqualTo(HttpStatus.OK);
        seedRun(ana, "completed");
        Object before = ana.status().get("progress");

        ResponseEntity<Map> stale = ana.post("/progress", "{\"step\":\"quiz\",\"question\":2}");

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ana.status().get("progress")).isEqualTo(before);
    }

    @Test
    void twoConcurrentFinishesStoreOneFinishedAtAndOneCompletedAt() throws Exception {
        Member ana = member("ana");

        List<ResponseEntity<Map>> both = concurrently(
                () -> ana.post("/progress", "{\"finished\":true}"),
                () -> ana.post("/progress", "{\"finished\":true}"));

        assertThat(both).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(finishedAt(both.get(0))).isNotNull().isEqualTo(finishedAt(both.get(1)));
        assertThat(both.get(0).getBody().get("completedAt")).isNotNull()
                .isEqualTo(both.get(1).getBody().get("completedAt"));
        Map<String, Object> status = ana.status();
        assertThat(((Map<String, Object>) status.get("progress")).get("finishedAt")).isEqualTo(finishedAt(both.get(0)));
        assertThat(status).containsEntry("completed", true);
    }

    @Test
    void twoConcurrentFirstWritesBothSucceedIntoOneRow() throws Exception {
        Member ana = member("ana");

        List<ResponseEntity<Map>> both = concurrently(
                () -> ana.patch("/quiz", "{\"source\":\"google\"}"),
                () -> ana.post("/progress", "{\"step\":\"quiz\",\"question\":2}"));

        assertThat(both).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(rows(ana)).isEqualTo(1);
        Map<String, Object> status = ana.status();
        assertThat(status).containsEntry("source", "google");
        assertThat((Map<String, Object>) status.get("progress")).containsEntry("step", "quiz").containsEntry("question", 2);
    }

    @Test
    void resetClearsAnswersAndProgressAndKeepsBuilderState() {
        Member ana = member("ana");
        ana.patch("/quiz", "{\"aiLevel\":\"tried\",\"industries\":[\"sales\"],\"usageType\":\"team\","
                + "\"finished\":true}");
        ana.post("/progress", "{\"finished\":true}");
        ResponseEntity<Map> builder = ana.post("/builder/progress", "{\"state\":{\"welcomeSeen\":true}}");
        assertThat(builder.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object builderDone = ana.post("/builder/complete", "{\"reason\":\"finished\"}").getBody().get("completedAt");

        ResponseEntity<Map> reset = ana.post("/reset", null);

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody()).containsEntry("completed", false);
        assertThat(ana.status()).containsEntry("completed", false).containsEntry("aiLevel", null)
                .containsEntry("industries", null).containsEntry("usageType", null)
                .containsEntry("progress", Map.of()).containsEntry("open", true);
        ResponseEntity<Map> again = ana.post("/builder/complete", "{}");
        assertThat(again.getBody().get("completedAt")).isEqualTo(builderDone);
        assertThat(again.getBody().get("state")).isEqualTo(Map.of("welcomeSeen", true, "reason", "finished"));
    }

    @Test
    void builderCompleteTwiceKeepsTheFirstTimestamp() {
        Member ana = member("ana");
        Map<String, Object> first = ana.post("/builder/complete", "{\"reason\":\"skipped\"}").getBody();

        Map<String, Object> second = ana.post("/builder/complete", "{\"reason\":\"finished\"}").getBody();

        assertThat(first.get("completedAt")).isNotNull();
        assertThat(second.get("completedAt")).isEqualTo(first.get("completedAt"));
        assertThat((Map<String, Object>) second.get("state")).containsEntry("reason", "skipped");
        assertThat(ana.post("/builder/complete", "{\"reason\":\"later\"}").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void anotherMembersAnswersAreNotVisible() {
        Member ana = member("ana");
        Member binh = member("binh");
        ana.patch("/quiz", "{\"aiLevel\":\"builder\"}");

        assertThat(ana.status()).containsEntry("aiLevel", "builder");
        assertThat(binh.status()).containsEntry("aiLevel", null);
    }

    @Test
    void invalidQuizBodyIs422NamingTheFieldAndStoresNothing() {
        Member ana = member("ana");
        ana.patch("/quiz", "{\"source\":\"google\"}");

        ResponseEntity<Map> refused = ana.patch("/quiz", "{\"aiLevel\":\"expert\",\"source\":\"tiktok\"}");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((Map<String, Object>) refused.getBody().get("errors")).containsOnlyKeys("aiLevel");
        assertThat(ana.status()).containsEntry("source", "google").containsEntry("aiLevel", null);
    }

    @Test
    void quizKeepsOrderStampsCompletionOnceAndASkipChangesNoAnswer() {
        Member ana = member("ana");
        ana.patch("/quiz", "{\"industries\":[\"other\",\"tech\",\"sales\"],\"aiLevel\":\"tried\"}");
        assertThat(ana.status()).containsEntry("industries", List.of("sales", "tech", "other"))
                .containsEntry("completed", false);

        Map<String, Object> skipped = ana.post("/quiz", "{\"skipped\":true,\"aiLevel\":\"builder\"}").getBody();
        Map<String, Object> finished = ana.patch("/quiz", "{\"finished\":true}").getBody();

        assertThat(skipped).containsEntry("completed", true).containsEntry("skipped", true);
        assertThat(finished.get("completedAt")).isEqualTo(skipped.get("completedAt"));
        assertThat(ana.status()).containsEntry("aiLevel", "tried");
    }

    @Test
    void builderStateMergesAndRefusesNonObjectAndOversizedState() {
        Member ana = member("ana");
        ana.post("/builder/progress", "{\"state\":{\"welcomeSeen\":true}}");

        Map<String, Object> merged = ana.post("/builder/progress", "{\"state\":{\"tip\":2}}").getBody();

        assertThat(merged.get("state")).isEqualTo(Map.of("welcomeSeen", true, "tip", 2));
        assertThat(ana.post("/builder/progress", "{\"state\":[1,2]}").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        String oversized = "{\"state\":{\"blob\":\"" + "x".repeat(8_200) + "\"}}";
        assertThat(ana.post("/builder/progress", oversized).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void resultFollowsTheFirstIndustryAndDefaultsToPersonal() {
        Member ana = member("ana");
        ana.patch("/quiz", "{\"industries\":[\"hr\",\"finance\"]}");

        ResponseEntity<Map> result = ana.get("/result");

        assertThat(result.getBody()).containsEntry("industry", "finance").containsEntry("track", "finance")
                .containsEntry("usageType", "personal").containsEntry("completed", false);
    }

    private record Member(UUID id, String token, OnboardingEndpointsIT test) {

        Map<String, Object> status() {
            ResponseEntity<Map> response = get("/status");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            return body;
        }

        ResponseEntity<Map> get(String route) {
            return test.call(HttpMethod.GET, BASE + route, token, null);
        }

        ResponseEntity<Map> post(String route, String body) {
            return test.call(HttpMethod.POST, BASE + route, token, body);
        }

        ResponseEntity<Map> patch(String route, String body) {
            return test.call(HttpMethod.PATCH, BASE + route, token, body);
        }
    }

    private Member member(String prefix) {
        UserView user = userService.createInitialAdmin(prefix + "-" + UUID.randomUUID() + "@example.com",
                "password123", prefix);
        ResponseEntity<TokenResponse> login = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(user.email(), "password123"), TokenResponse.class);
        return new Member(user.id(), login.getBody().accessToken(), this);
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    private void seedRun(Member owner, String status) {
        WorkflowView workflow = workflowService.createWorkflow(owner.id(), "onboarding-" + UUID.randomUUID(), false,
                List.of(new NewWorkflowStep("Echo", "echo", null)));
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at) "
                + "VALUES (?, ?, ?, ?, 'x', ?)", UUID.randomUUID(), workflow.id(), owner.id(), status,
                Timestamp.from(Instant.now()));
    }

    private long rows(Member member) {
        return jdbc.queryForObject("SELECT count(*) FROM user_onboarding WHERE user_id = ?", Long.class, member.id());
    }

    private static Object finishedAt(ResponseEntity<Map> response) {
        return ((Map<String, Object>) response.getBody().get("progress")).get("finishedAt");
    }

    /** Runs the calls on separate threads released together. */
    @SafeVarargs
    private static List<ResponseEntity<Map>> concurrently(Callable<ResponseEntity<Map>>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> futures = new java.util.ArrayList<>();
            for (Callable<ResponseEntity<Map>> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<ResponseEntity<Map>> results = new java.util.ArrayList<>();
            for (Future<ResponseEntity<Map>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
