package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The member run-history endpoints under {@code /api/v1/runs} on the real app, with credits off. Runs are
 * written straight to the database, since only the reading and the member's own writes are under test; every
 * test owns a fresh user, so owner-scoped reads see only its rows.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RunHistoryEndpointsIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    record PageOf<T>(List<T> items, int page, int size, long total) {
    }

    record RowView(
            UUID id, UUID workflowId, String workflowName, String title, String status, boolean favorite,
            Instant startedAt, Instant finishedAt, Long durationSeconds, Long creditsCharged) {
    }

    record OptionView(UUID id, String name) {
    }

    record TrayView(List<RowView> active, List<RowView> recent) {
    }

    record DayView(String day, long runs, long failed, Long credits) {
    }

    record TopView(UUID workflowId, String name, long runs, Long credits, Double averageCredits) {
    }

    record UsageView(
            int days, String from, String to, long runs, long failed, double failureRate, Long creditsSpent,
            Long balance, List<DayView> series, List<TopView> topWorkflows) {
    }

    record ProblemView(int status, String detail, Map<String, String> errors) {
    }

    private static final ParameterizedTypeReference<PageOf<RowView>> ROW_PAGE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<OptionView>> OPTIONS = new ParameterizedTypeReference<>() {
    };

    @Test
    void listShowsOnlyTheCallersRunsWithFilters() {
        UserView m = newUser("hist-list");
        UserView n = newUser("hist-list-other");
        WorkflowView w = workflowOf(m);
        Instant d1 = Instant.parse("2026-11-01T10:00:00Z");
        Instant d2 = Instant.parse("2026-11-02T10:00:00Z");
        Instant d3 = Instant.parse("2026-11-03T10:00:00Z");
        UUID first = seedRun(w.id(), m.id(), "completed", d1, d1.plusSeconds(150));
        UUID second = seedRun(w.id(), m.id(), "error", d2, d2.plusSeconds(10));
        jdbc.update("UPDATE workflow_run SET favorite = true, title = 'Launch cut' WHERE id = ?", second);
        UUID third = seedRun(w.id(), m.id(), "completed", d3, d3.plusSeconds(10));
        seedRun(w.id(), n.id(), "completed", d2, d2.plusSeconds(10));
        String token = loginAs(m);

        PageOf<RowView> all = page("/api/v1/runs", token);
        PageOf<RowView> filtered = page("/api/v1/runs?workflowId=" + w.id()
                + "&from=2026-11-02T00:00:00Z&to=2026-11-03T10:00:00Z", token);
        PageOf<RowView> starred = page("/api/v1/runs?favorite=true", token);
        PageOf<RowView> failed = page("/api/v1/runs?status=error", token);
        PageOf<RowView> secondPage = page("/api/v1/runs?size=2&page=1", token);

        assertThat(all.items()).extracting(RowView::id).containsExactly(third, second, first);
        assertThat(all.total()).isEqualTo(3);
        assertThat(filtered.items()).extracting(RowView::id).containsExactly(second);
        assertThat(filtered.total()).isEqualTo(1);
        assertThat(starred.items()).extracting(RowView::id).containsExactly(second);
        assertThat(failed.items()).extracting(RowView::id).containsExactly(second);
        assertThat(secondPage.items()).extracting(RowView::id).containsExactly(first);
        assertThat(secondPage.total()).isEqualTo(3);
        RowView row = all.items().get(1);
        assertThat(row.workflowName()).isEqualTo(w.name());
        assertThat(row.title()).isEqualTo("Launch cut");
        assertThat(row.status()).isEqualTo("error");
        assertThat(row.favorite()).isTrue();
        assertThat(row.durationSeconds()).isEqualTo(10);
        assertThat(row.creditsCharged()).as("credits are off").isNull();
        assertThat(all.items().get(2).durationSeconds()).isEqualTo(150);
        assertThat(get("/api/v1/runs", null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRunningRunHasNoDuration() {
        UserView m = newUser("hist-running");
        seedRun(workflowOf(m).id(), m.id(), "running", Instant.now().minusSeconds(30), null);

        assertThat(page("/api/v1/runs", loginAs(m)).items()).singleElement()
                .satisfies(row -> assertThat(row.durationSeconds()).isNull());
    }

    @Test
    void malformedFiltersAreRejectedNamingTheParameter() {
        String token = loginAs(newUser("hist-malformed"));
        Map<String, String> cases = Map.of(
                "/api/v1/runs?status=done", "status",
                "/api/v1/runs?from=2026-11-03T00:00:00Z&to=2026-11-02T00:00:00Z", "from",
                "/api/v1/runs?workflowId=nope", "workflowId",
                "/api/v1/runs?from=yesterday", "from",
                "/api/v1/runs?size=101", "size",
                "/api/v1/runs/export?status=done", "status",
                "/api/v1/runs/usage-summary?days=abc", "days",
                "/api/v1/runs/usage-summary?top=1.5", "top");

        cases.forEach((path, parameter) -> {
            ResponseEntity<ProblemView> response = get(path, token, ProblemView.class);

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().errors()).as(path).containsKey(parameter);
        });
    }

    @Test
    void workflowChoicesAreDistinctByNameAndKeepADeletedWorkflow() {
        UserView m = newUser("hist-options");
        WorkflowView dub = workflowNamed(m, "Dub");
        WorkflowView ads = workflowNamed(m, "Ads");
        seedRun(dub.id(), m.id(), "completed", Instant.now().minusSeconds(60), Instant.now());
        seedRun(dub.id(), m.id(), "completed", Instant.now().minusSeconds(50), Instant.now());
        seedRun(ads.id(), m.id(), "completed", Instant.now().minusSeconds(40), Instant.now());
        jdbc.update("UPDATE workflow SET deleted_at = now() WHERE id = ?", ads.id());

        List<OptionView> options = restTemplate.exchange("/api/v1/runs/workflows", HttpMethod.GET,
                bearer(loginAs(m)), OPTIONS).getBody();

        assertThat(options).extracting(OptionView::name).containsExactly("Ads", "Dub");
    }

    @Test
    void patchRenamesAndStarsAndRefusesAnotherMembersRun() {
        UserView m = newUser("hist-patch");
        UserView n = newUser("hist-patch-other");
        UUID run = seedRun(workflowOf(m).id(), m.id(), "running", Instant.now().minusSeconds(60), null);
        String token = loginAs(m);
        String path = "/api/v1/runs/" + run;

        ResponseEntity<RowView> renamed = patch(path, token, "{\"title\":\"  Launch cut  \",\"favorite\":true}",
                RowView.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().title()).isEqualTo("Launch cut");
        assertThat(renamed.getBody().favorite()).isTrue();
        assertThat(renamed.getBody().status()).isEqualTo("running");

        ResponseEntity<RowView> cleared = patch(path, token, "{\"title\":\"\"}", RowView.class);
        assertThat(cleared.getBody().title()).isNull();
        assertThat(cleared.getBody().favorite()).as("an unsent field stays").isTrue();
        patch(path, token, "{\"title\":\"Again\"}", RowView.class);
        assertThat(patch(path, token, "{\"title\":null}", RowView.class).getBody().title()).isNull();
        assertThat(patch(path, token, "{\"favorite\":false}", RowView.class).getBody().favorite()).isFalse();

        for (String body : List.of("{\"title\":\"" + "x".repeat(121) + "\"}", "{\"title\":\"a\\nb\"}", "{}",
                "{\"favorite\":null}")) {
            ResponseEntity<ProblemView> refused = patch(path, token, body, ProblemView.class);
            assertThat(refused.getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody().errors()).as(body).hasSize(1);
        }
        assertThat(jdbc.queryForMap("SELECT title, favorite, status FROM workflow_run WHERE id = ?", run))
                .containsEntry("favorite", false).containsEntry("status", "running");
        assertThat(jdbc.queryForObject("SELECT title FROM workflow_run WHERE id = ?", String.class, run)).isNull();

        assertThat(patch(path, loginAs(n), "{\"title\":\"mine\"}", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch("/api/v1/runs/" + UUID.randomUUID(), token, "{\"title\":\"x\"}", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteFinishedThen404AndRunPageAndOutputLink404() {
        UserView m = newUser("hist-delete");
        WorkflowView w = workflowOf(m);
        UUID kept = seedRun(w.id(), m.id(), "completed", Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
        UUID run = seedRun(w.id(), m.id(), "completed", Instant.now().minusSeconds(60), Instant.now());
        String token = loginAs(m);
        assertThat(get("/api/v1/runs/" + run, token, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(delete("/api/v1/runs/" + run, token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(page("/api/v1/runs", token).items()).extracting(RowView::id).containsExactly(kept);
        assertThat(delete("/api/v1/runs/" + run, token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/runs/" + run, token, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/runs/" + run + "/steps/1/outputs/source-audio.wav/url", token, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch("/api/v1/runs/" + run, token, "{\"favorite\":true}", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE id = ? AND deleted_at IS NOT NULL",
                Long.class, run)).as("the row stays for the engine and the admin").isEqualTo(1);
        assertThat(delete("/api/v1/runs/" + kept, loginAs(newUser("hist-delete-other"))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete("/api/v1/runs/" + kept, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deleteRunningIs409() {
        UserView m = newUser("hist-active");
        WorkflowView w = workflowOf(m);
        String token = loginAs(m);

        for (String status : List.of("running", "waiting_approval", "cancelling")) {
            UUID run = seedRun(w.id(), m.id(), status, Instant.now().minusSeconds(60), null);

            ResponseEntity<ProblemView> response = delete("/api/v1/runs/" + run, token, ProblemView.class);

            assertThat(response.getStatusCode()).as(status).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().detail()).as(status).contains("still active");
            assertThat(page("/api/v1/runs?status=" + status, token).items()).as(status).hasSize(1);
        }
    }

    @Test
    void exportCapsAtAThousandWithTotalHeader() {
        UserView m = newUser("hist-export");
        WorkflowView w = workflowNamed(m, "Lồng tiếng, bản 2");
        Instant base = Instant.parse("2026-10-01T00:00:00Z");
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            Instant start = base.plusSeconds(i);
            batch.add(new Object[] {UUID.randomUUID(), w.id(), m.id(), "completed", "seed", Timestamp.from(start),
                    Timestamp.from(start.plusSeconds(5))});
        }
        jdbc.batchUpdate("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, "
                + "finished_at) VALUES (?, ?, ?, ?, ?, ?, ?)", batch);
        UUID formula = seedRun(w.id(), m.id(), "error", base.plusSeconds(5000), base.plusSeconds(5010));
        jdbc.update("UPDATE workflow_run SET title = '=HYPERLINK(\"x\")', error_message = ? WHERE id = ?",
                "Step 1 failed,\nbadly", formula);
        String token = loginAs(m);

        ResponseEntity<byte[]> response = restTemplate.exchange("/api/v1/runs/export", HttpMethod.GET,
                bearer(token), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("1201");
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv").containsIgnoringCase("utf-8");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .matches("attachment; filename=\"runs-\\d{8}\\.csv\"");
        byte[] body = response.getBody();
        assertThat(body[0] & 0xff).isEqualTo(0xEF);
        assertThat(body[1] & 0xff).isEqualTo(0xBB);
        assertThat(body[2] & 0xff).isEqualTo(0xBF);
        String csv = new String(body, StandardCharsets.UTF_8).substring(1);
        assertThat(csv).startsWith("\"Run ID\",\"Workflow\",\"Title\",\"Status\",\"Favorite\",\"Started at\","
                + "\"Finished at\",\"Duration (s)\",\"Credits charged\",\"Error\"\r\n");
        assertThat(csv).contains("\"Lồng tiếng, bản 2\"").contains("\"'=HYPERLINK(\"\"x\"\")\"")
                .contains("\"Step 1 failed,\nbadly\"");
        assertThat(csv.split("\r\n")).as("header plus the newest 1000 runs").hasSize(1001);
        assertThat(csv.split("\r\n")[1]).contains(formula.toString());

        ResponseEntity<byte[]> filtered = restTemplate.exchange("/api/v1/runs/export?status=error", HttpMethod.GET,
                bearer(token), byte[].class);
        assertThat(filtered.getHeaders().getFirst("X-Total-Count")).isEqualTo("1");
        assertThat(new String(filtered.getBody(), StandardCharsets.UTF_8).split("\r\n")).hasSize(2);
    }

    @Test
    void usageSummaryCountsADeletedRun() {
        UserView m = newUser("hist-usage");
        UserView other = newUser("hist-usage-other");
        WorkflowView w = workflowNamed(m, "Usage W");
        WorkflowView v = workflowNamed(m, "Usage V");
        Instant now = Instant.now();
        seedRun(w.id(), m.id(), "completed", now.minusSeconds(3600), now.minusSeconds(3500));
        seedRun(w.id(), m.id(), "error", now.minusSeconds(3000), now.minusSeconds(2900));
        UUID deleted = seedRun(w.id(), m.id(), "completed", now.minusSeconds(2000), now.minusSeconds(1900));
        seedRun(v.id(), m.id(), "completed", now.minusSeconds(1000), now.minusSeconds(900));
        seedRun(w.id(), m.id(), "completed", now.minus(Duration.ofDays(60)), now.minus(Duration.ofDays(60)));
        seedRun(w.id(), other.id(), "completed", now.minusSeconds(100), now.minusSeconds(50));
        String token = loginAs(m);
        assertThat(delete("/api/v1/runs/" + deleted, token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        UsageView usage = get("/api/v1/runs/usage-summary", token, UsageView.class).getBody();

        assertThat(usage.days()).isEqualTo(30);
        assertThat(usage.runs()).as("the deleted run still counts, the 60-day-old one is outside").isEqualTo(4);
        assertThat(usage.failed()).isEqualTo(1);
        assertThat(usage.failureRate()).isEqualTo(25.0);
        assertThat(usage.creditsSpent()).isNull();
        assertThat(usage.balance()).isNull();
        assertThat(usage.series()).hasSize(30);
        assertThat(usage.series().stream().mapToLong(DayView::runs).sum()).isEqualTo(4);
        assertThat(usage.topWorkflows()).extracting(TopView::name).containsExactly("Usage W", "Usage V");
        assertThat(usage.topWorkflows().get(0).runs()).isEqualTo(3);

        UsageView clamped = get("/api/v1/runs/usage-summary?days=0&top=500", token, UsageView.class).getBody();
        assertThat(clamped.days()).isEqualTo(1);
        assertThat(clamped.series()).hasSize(1);
        assertThat(get("/api/v1/runs/usage-summary?days=400", token, UsageView.class).getBody().series())
                .hasSize(365);
    }

    @Test
    void trayBucketsOverHttp() {
        UserView m = newUser("hist-tray");
        WorkflowView w = workflowOf(m);
        Instant now = Instant.now();
        UUID running = seedRun(w.id(), m.id(), "running", now.minusSeconds(600), null);
        UUID waiting = seedRun(w.id(), m.id(), "waiting_approval", now.minusSeconds(300), null);
        UUID completed = seedRun(w.id(), m.id(), "completed", now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(1)));
        UUID longRun = seedRun(w.id(), m.id(), "error", now.minus(Duration.ofHours(30)), now.minusSeconds(300));
        seedRun(w.id(), m.id(), "cancelled", now.minus(Duration.ofHours(40)), now.minus(Duration.ofHours(25)));
        UUID removed = seedRun(w.id(), m.id(), "completed", now.minusSeconds(120), now.minusSeconds(60));
        String token = loginAs(m);
        assertThat(delete("/api/v1/runs/" + removed, token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        TrayView tray = get("/api/v1/runs/tray", token, TrayView.class).getBody();

        assertThat(tray.active()).extracting(RowView::id).containsExactly(waiting, running);
        assertThat(tray.recent()).extracting(RowView::id).containsExactly(longRun, completed);
        assertThat(get("/api/v1/runs/tray", null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private WorkflowView workflowOf(UserView owner) {
        return newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
    }

    private WorkflowView workflowNamed(UserView owner, String name) {
        return workflowService.createWorkflow(owner.id(), name, false,
                List.of(new NewWorkflowStep("only", "echo", null)));
    }

    private PageOf<RowView> page(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET, bearer(token), ROW_PAGE).getBody();
    }

    private <T> ResponseEntity<T> patch(String path, String token, String json, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(json, headers), type);
    }

    private ResponseEntity<String> delete(String path, String token) {
        return delete(path, token, String.class);
    }

    private <T> ResponseEntity<T> delete(String path, String token, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.DELETE, bearer(token), type);
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(headers);
    }

    private UUID seedRun(UUID workflowId, UUID ownerId, String status, Instant startedAt, Instant finishedAt) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                        + "VALUES (?, ?, ?, ?, 'seed', ?, ?)",
                runId, workflowId, ownerId, status, Timestamp.from(startedAt),
                finishedAt == null ? null : Timestamp.from(finishedAt));
        return runId;
    }
}
