package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code /api/v1/builder/dashboard}: a member's own workflow totals and daily run chart. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class BuilderDashboardIT extends AbstractWorkflowHttpIT {

    private static final String SUMMARY = "/api/v1/builder/dashboard/summary";
    private static final String CHART = "/api/v1/builder/dashboard/runs-chart";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private JdbcTemplate jdbc;

    record Summary(long totalWorkflows, long publishedWorkflows, long totalRuns) {
    }

    record Day(LocalDate day, long runs, long failed) {
    }

    record Chart(String period, List<Day> series) {
    }

    record ProblemView(int status, String detail, Map<String, String> errors) {
    }

    @Test
    void theSummaryCountsOwnedNonDeletedWorkflowsAndRunsByAnyMember() {
        UserView builder = newUser("builder-summary");
        UserView runner = newUser("runner-summary");
        UUID published = insertWorkflow(builder.id(), "published", false);
        UUID draft = insertWorkflow(builder.id(), "draft", false);
        UUID deleted = insertWorkflow(builder.id(), "published", true);
        UUID someoneElses = insertWorkflow(runner.id(), "published", false);
        insertRun(published, builder.id(), "completed", Instant.now());
        insertRun(published, runner.id(), "error", Instant.now());
        insertRun(draft, builder.id(), "running", Instant.now());
        insertRun(deleted, builder.id(), "completed", Instant.now());
        insertRun(someoneElses, builder.id(), "completed", Instant.now());

        ResponseEntity<Summary> response = get(SUMMARY, loginAs(builder), Summary.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new Summary(2, 1, 3));
    }

    @Test
    void aMemberWithNoWorkflowGetsZeros() {
        UserView member = newUser("builder-empty");
        String token = loginAs(member);

        Summary summary = get(SUMMARY, token, Summary.class).getBody();
        Chart chart = get(CHART + "?period=7d", token, Chart.class).getBody();

        assertThat(summary).isEqualTo(new Summary(0, 0, 0));
        assertThat(chart.series()).hasSize(7);
        assertThat(chart.series()).allSatisfy(day -> assertThat(day.runs()).isZero());
    }

    @Test
    void theSevenDayChartIsZeroFilledAndCountsOnlyTheCallersWorkflows() {
        UserView builder = newUser("builder-chart");
        UserView other = newUser("other-chart");
        UUID mine = insertWorkflow(builder.id(), "published", false);
        UUID theirs = insertWorkflow(other.id(), "published", false);
        LocalDate today = LocalDate.now(ZONE);
        insertRun(mine, other.id(), "completed", noonOf(today));
        insertRun(mine, builder.id(), "error", noonOf(today));
        insertRun(mine, builder.id(), "completed", noonOf(today.minusDays(3)));
        insertRun(mine, builder.id(), "completed", noonOf(today.minusDays(7)));
        insertRun(theirs, other.id(), "completed", noonOf(today));

        Chart chart = get(CHART + "?period=7d", loginAs(builder), Chart.class).getBody();

        assertThat(chart.period()).isEqualTo("7d");
        assertThat(chart.series()).hasSize(7);
        assertThat(chart.series().get(0).day()).isEqualTo(today.minusDays(6));
        assertThat(chart.series().get(6)).isEqualTo(new Day(today, 2, 1));
        assertThat(chart.series().get(3)).isEqualTo(new Day(today.minusDays(3), 1, 0));
        assertThat(chart.series().stream().mapToLong(Day::runs).sum()).isEqualTo(3);
        assertThat(get(CHART, loginAs(builder), Chart.class).getBody().series()).hasSize(30);
        assertThat(get(CHART + "?period=90d", loginAs(builder), Chart.class).getBody().series()).hasSize(90);
    }

    @Test
    void aRunAtHalfPastMidnightLocalTimeCountsOnTheLocalDay() {
        UserView builder = newUser("builder-midnight");
        UUID workflow = insertWorkflow(builder.id(), "published", false);
        LocalDate day = LocalDate.now(ZONE).minusDays(2);
        Instant justAfterMidnight = day.atTime(0, 30).atZone(ZONE).toInstant();
        Instant justBeforeMidnight = day.atTime(23, 30).atZone(ZONE).toInstant();
        insertRun(workflow, builder.id(), "completed", justAfterMidnight);
        insertRun(workflow, builder.id(), "completed", justBeforeMidnight);

        Chart chart = get(CHART + "?period=7d", loginAs(builder), Chart.class).getBody();

        assertThat(chart.series().get(4)).isEqualTo(new Day(day, 2, 0));
        assertThat(chart.series().get(3).runs()).isZero();
        assertThat(chart.series().get(5).runs()).isZero();
    }

    @Test
    void anUnknownPeriodIsA400() {
        String token = loginAs(newUser("builder-period"));

        ResponseEntity<ProblemView> response = get(CHART + "?period=14d", token, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors()).containsKey("period");
        assertThat(get(SUMMARY, null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static Instant noonOf(LocalDate day) {
        return day.atTime(12, 0).atZone(ZONE).toInstant();
    }

    private UUID insertWorkflow(UUID ownerId, String status, boolean deleted) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status, deleted_at) "
                + "VALUES (?, ?, 'Builder fixture', false, ?, ?, ?, ?)", id, ownerId, now, now, status,
                deleted ? now : null);
        return id;
    }

    private void insertRun(UUID workflowId, UUID ownerId, String status, Instant startedAt) {
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                        + "VALUES (?, ?, ?, ?, 'seed', ?, ?)", UUID.randomUUID(), workflowId, ownerId, status,
                Timestamp.from(startedAt), "running".equals(status) ? null : Timestamp.from(startedAt));
    }
}
