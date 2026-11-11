package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flowcortex.user.api.UserView;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code /api/v1/admin/workflows} reads: the workflow list, its CSV export and the per-workflow usage report.
 * The admin list spans every row of the shared database, so each test names its workflows with a tag of its
 * own and searches by it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(AdminRunsEndpointsIT.StatementLogConfig.class)
class AdminWorkflowReportsIT extends AbstractWorkflowHttpIT {

    private static final String LIST = "/api/v1/admin/workflows";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminRunsEndpointsIT.StatementLog statements;

    record Page(List<Row> items, int page, int size, long total) {
    }

    record Row(
            UUID id, String name, UUID ownerId, String ownerEmail, String status, boolean shared, int steps,
            long runs, long runsLast7Days, Instant lastRunAt, Instant createdAt, Instant updatedAt) {
    }

    record Day(LocalDate day, long runs, long failed) {
    }

    record Report(Row workflow, Map<String, Long> countsByStatus, Instant lastRunAt, List<Day> series) {
    }

    record ProblemView(int status, String detail, Map<String, String> errors) {
    }

    @Test
    void listsEveryOwnersWorkflowsInAnyStatusAndNeverADeletedOne() {
        UserView admin = newUser("admin-wfr-list");
        UserView other = newUser("owner-wfr-list");
        String tag = tag();
        UUID draft = insertWorkflow(admin.id(), tag + " draft", "draft", false, false);
        UUID published = insertWorkflow(other.id(), tag + " published", "published", true, false);
        insertWorkflow(other.id(), tag + " deleted", "published", true, true);
        insertStep(published, 1);
        insertStep(published, 2);
        String token = loginAs(admin);

        ResponseEntity<Page> response = get(LIST + "?size=100&q=" + tag, token, Page.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Page page = response.getBody();
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(Row::id).containsExactlyInAnyOrder(draft, published);
        Row publishedRow = page.items().stream().filter(row -> row.id().equals(published)).findFirst().orElseThrow();
        assertThat(publishedRow.ownerEmail()).isEqualTo(other.email());
        assertThat(publishedRow.ownerId()).isEqualTo(other.id());
        assertThat(publishedRow.status()).isEqualTo("published");
        assertThat(publishedRow.shared()).isTrue();
        assertThat(publishedRow.steps()).isEqualTo(2);
        assertThat(get(LIST + "?size=100&status=draft&q=" + tag, token, Page.class).getBody().items())
                .extracting(Row::id).containsExactly(draft);
    }

    @Test
    void rowsCarryRunTotalsRunsInTheLastSevenDaysAndTheLastRun() {
        UserView admin = newUser("admin-wfr-counts");
        String tag = tag();
        UUID workflow = insertWorkflow(admin.id(), tag, "published", false, false);
        UUID idle = insertWorkflow(admin.id(), tag + " idle", "published", false, false);
        Instant now = Instant.now();
        Instant recent = now.minus(Duration.ofDays(1));
        insertRun(workflow, admin.id(), "completed", now.minus(Duration.ofHours(2)));
        insertRun(workflow, admin.id(), "error", recent);
        insertRun(workflow, admin.id(), "completed", now.minus(Duration.ofDays(20)));
        String token = loginAs(admin);

        Page page = get(LIST + "?size=100&q=" + tag, token, Page.class).getBody();

        Row row = page.items().stream().filter(item -> item.id().equals(workflow)).findFirst().orElseThrow();
        assertThat(row.runs()).isEqualTo(3);
        assertThat(row.runsLast7Days()).isEqualTo(2);
        assertThat(row.lastRunAt()).isCloseTo(now.minus(Duration.ofHours(2)), within(1, ChronoUnit.SECONDS));
        Row idleRow = page.items().stream().filter(item -> item.id().equals(idle)).findFirst().orElseThrow();
        assertThat(idleRow.runs()).isZero();
        assertThat(idleRow.runsLast7Days()).isZero();
        assertThat(idleRow.lastRunAt()).isNull();
    }

    @Test
    void aNameSearchMatchesPercentLiterally() {
        UserView admin = newUser("admin-wfr-search");
        String tag = tag();
        UUID literal = insertWorkflow(admin.id(), tag + "_100% sure", "published", false, false);
        insertWorkflow(admin.id(), tag + "X100 sure", "published", false, false);
        String token = loginAs(admin);

        Page underscoreAndPercent = get(LIST + "?size=100&q=" + tag + "_100%", token, Page.class).getBody();
        Page percentOnly = get(LIST + "?size=100&q=" + tag + "X100%", token, Page.class).getBody();
        Page otherCase = get(LIST + "?size=100&q=" + tag.toUpperCase(), token, Page.class).getBody();

        assertThat(underscoreAndPercent.items()).extracting(Row::id).containsExactly(literal);
        assertThat(percentOnly.items()).isEmpty();
        assertThat(otherCase.total()).isEqualTo(2);
    }

    @Test
    void aBadStatusOrSizeIsA400NamingIt() {
        UserView admin = newUser("admin-wfr-bad");
        String token = loginAs(admin);

        ResponseEntity<ProblemView> status = getProblem(LIST + "?status=archived", token);
        ResponseEntity<ProblemView> size = getProblem(LIST + "?size=500", token);
        ResponseEntity<ProblemView> tooLongQuery = getProblem(LIST + "?q=" + "a".repeat(256), token);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status.getBody().errors()).containsKey("status");
        assertThat(size.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(size.getBody().errors()).containsKey("size");
        assertThat(tooLongQuery.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLongQuery.getBody().errors()).containsKey("q");
    }

    @Test
    void theExportHoldsTheFilteredRowsWithItsHeaderAndTotal() {
        UserView admin = newUser("admin-wfr-export");
        String tag = tag();
        insertWorkflow(admin.id(), tag + " =SUM(1)", "published", true, false);
        insertWorkflow(admin.id(), tag + " plain", "draft", false, false);
        String token = loginAs(admin);

        ResponseEntity<byte[]> response = get(LIST + "/export?status=published&q=" + tag, token, byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("1");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .matches("attachment; filename=\"workflows-\\d{8}\\.csv\"");
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿\"ID\",\"Name\",\"Owner email\",\"Status\",\"Shared\",\"Steps\","
                + "\"Runs\",\"Runs 7d\",\"Last run at\",\"Created at\",\"Updated at\"\r\n");
        String[] lines = body.split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains("\"" + tag + " =SUM(1)\"").contains("\"published\"")
                .contains("\"" + admin.email() + "\"");
    }

    @Test
    void theReportCountsRunsByStatusAndFillsThirtyDays() {
        UserView admin = newUser("admin-wfr-report");
        String tag = tag();
        UUID workflow = insertWorkflow(admin.id(), tag, "published", false, false);
        LocalDate today = LocalDate.now(ZONE);
        insertRun(workflow, admin.id(), "completed", noonOf(today));
        insertRun(workflow, admin.id(), "error", noonOf(today));
        insertRun(workflow, admin.id(), "running", noonOf(today.minusDays(2)));
        insertRun(workflow, admin.id(), "cancelled", noonOf(today.minusDays(29)));
        insertRun(workflow, admin.id(), "completed", noonOf(today.minusDays(30)));
        String token = loginAs(admin);

        ResponseEntity<Report> response = get(LIST + "/" + workflow + "/report", token, Report.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Report report = response.getBody();
        assertThat(report.workflow().id()).isEqualTo(workflow);
        assertThat(report.workflow().runs()).isEqualTo(5);
        assertThat(report.countsByStatus()).containsOnlyKeys("running", "waiting_approval", "completed", "error",
                "cancelling", "cancelled");
        assertThat(report.countsByStatus()).containsEntry("completed", 2L).containsEntry("error", 1L)
                .containsEntry("running", 1L).containsEntry("cancelled", 1L).containsEntry("cancelling", 0L)
                .containsEntry("waiting_approval", 0L);
        assertThat(report.lastRunAt()).isNotNull();
        assertThat(report.series()).hasSize(30);
        assertThat(report.series().get(0).day()).isEqualTo(today.minusDays(29));
        assertThat(report.series().get(29).day()).isEqualTo(today);
        assertThat(report.series().get(29)).extracting(Day::runs, Day::failed).containsExactly(2L, 1L);
        assertThat(report.series().get(27)).extracting(Day::runs, Day::failed).containsExactly(1L, 0L);
        assertThat(report.series().get(0)).extracting(Day::runs, Day::failed).containsExactly(1L, 0L);
        assertThat(report.series().stream().mapToLong(Day::runs).sum()).isEqualTo(4);
        assertThat(get(LIST + "/" + UUID.randomUUID() + "/report", token, ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aDeletedWorkflowsReportIs404() {
        UserView admin = newUser("admin-wfr-deleted");
        UUID deleted = insertWorkflow(admin.id(), tag(), "published", false, true);
        String token = loginAs(admin);

        ResponseEntity<ProblemView> response = getProblem(LIST + "/" + deleted + "/report", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCustomerSupportReadsTheListButCannotExport() {
        UserView admin = newUser("admin-wfr-support");
        UserView support = userService.createUser(admin.id(), uniqueEmail("support-wfr"), "password123", "Support",
                "CUSTOMER_SUPPORT");
        UUID workflow = insertWorkflow(admin.id(), tag(), "published", false, false);
        String token = loginAs(support);

        assertThat(get(LIST + "?size=1", token, Page.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(LIST + "/" + workflow + "/report", token, Report.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get(LIST + "/export", token, byte[].class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(LIST + "?size=1", loginAs(newMember(admin)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(LIST + "?size=1", null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void onePageOfTwentyWorkflowsCostsAtMostFiveStatements() {
        UserView admin = newUser("admin-wfr-statements");
        String tag = tag();
        for (int i = 0; i < 20; i++) {
            UserView owner = userService.createUser(admin.id(), uniqueEmail("wfr-owner-" + i), "password123",
                    "Owner " + i, "MEMBER");
            UUID workflow = insertWorkflow(owner.id(), tag + " " + i, "published", false, false);
            insertStep(workflow, 1);
            insertRun(workflow, owner.id(), "completed", Instant.now());
        }
        String token = loginAs(admin);
        statements.clear();

        Page page = get(LIST + "?page=0&size=20&q=" + tag, token, Page.class).getBody();

        assertThat(page.items()).hasSize(20);
        assertThat(page.items()).allSatisfy(row -> {
            assertThat(row.ownerEmail()).isNotNull();
            assertThat(row.runs()).isEqualTo(1);
            assertThat(row.steps()).isEqualTo(1);
        });
        List<String> sql = statements.forRequest(LIST);
        assertThat(sql).as("statements for one page: %s", sql).hasSizeLessThanOrEqualTo(5);
    }

    private UserView newMember(UserView admin) {
        return userService.createUser(admin.id(), uniqueEmail("member-wfr"), "password123", "Member", "MEMBER");
    }

    private static String tag() {
        return "wfr" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static Instant noonOf(LocalDate day) {
        return day.atTime(12, 0).atZone(ZONE).toInstant();
    }

    private ResponseEntity<ProblemView> getProblem(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<ProblemView>() {
                });
    }

    private UUID insertWorkflow(UUID ownerId, String name, String status, boolean shared, boolean deleted) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status, deleted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, ownerId, name, shared, now, now, status,
                deleted ? now : null);
        return id;
    }

    private void insertStep(UUID workflowId, int order) {
        jdbc.update("INSERT INTO workflow_step (id, workflow_id, step_order, name, type) "
                + "VALUES (?, ?, ?, 'Step', 'echo')", UUID.randomUUID(), workflowId, order);
    }

    private void insertRun(UUID workflowId, UUID ownerId, String status, Instant startedAt) {
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                        + "VALUES (?, ?, ?, ?, 'seed', ?, ?)", UUID.randomUUID(), workflowId, ownerId, status,
                Timestamp.from(startedAt), "running".equals(status) ? null : Timestamp.from(startedAt));
    }
}
