package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.ttddyy.observation.tracing.QueryContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code /api/v1/admin/runs} and {@code /api/v1/admin/media/jobs} on the real app: every user's
 * runs and every remote step, newest first, for an ADMIN only.
 *
 * <p>The admin lists see every row in the shared database, so each test seeds its rows later
 * than anything seeded before it ({@link #nextBase()}); its own rows are then the newest. A total
 * is checked by how much the test's own rows move it, whatever other tests left behind.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminRunsEndpointsIT extends AbstractWorkflowHttpIT {

    private static final Instant FAR_FUTURE = Instant.parse("2200-01-01T00:00:00Z");
    private static final AtomicInteger FIXTURE = new AtomicInteger();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StatementLog statements;

    record PageView<T>(List<T> items, int page, int size, long total) {
    }

    record AdminRunItem(
            UUID id, UUID workflowId, String workflowName, UUID ownerId, String ownerEmail, String status,
            String errorMessage, Instant startedAt, Instant finishedAt, Long creditsCharged) {
    }

    record MediaJobItem(
            UUID jobId, UUID runId, int stepOrder, String capability, String status, int attempt,
            int deliveryCount, Instant pickedUpAt, Instant queueDeadline, Instant leaseUntil, Instant runDeadline,
            Integer progressPercent, String errorCode, String errorMessage, Instant startedAt, Instant finishedAt) {
    }

    record ProblemView(int status, String detail, Map<String, String> errors, List<Object> items) {
    }

    private static final ParameterizedTypeReference<PageView<AdminRunItem>> RUN_PAGE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<PageView<MediaJobItem>> JOB_PAGE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<PageView<Object>> ANY_PAGE =
            new ParameterizedTypeReference<>() {
            };

    @Test
    void anAdminSeesEveryUsersRunsNewestFirstWithTheOwnerEmailEvenForASuspendedOrDeletedOwner() {
        UserView admin = newUser("admin-runs");
        UserView active = userService.createUser(admin.id(), uniqueEmail("owner-active"), "password123",
                "Active", "MEMBER");
        UserView suspended = userService.createUser(admin.id(), uniqueEmail("owner-suspended"), "password123",
                "Suspended", "MEMBER");
        userService.updateUser(admin.id(), suspended.id(), null, null, null, "SUSPENDED");
        UUID deletedOwner = UUID.randomUUID();
        WorkflowView workflow = newWorkflow(admin.id(), true, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(admin);
        long before = total("/api/v1/admin/runs?size=1", token);
        Instant base = nextBase();
        UUID oldest = seedRun(workflow.id(), active.id(), "completed", null, base);
        UUID middle = seedRun(workflow.id(), suspended.id(), "error", "Step 1 failed: boom", base.plusSeconds(60));
        UUID newest = seedRun(workflow.id(), deletedOwner, "completed", null, base.plusSeconds(120));

        ResponseEntity<PageView<AdminRunItem>> response =
                getPage("/api/v1/admin/runs?page=0&size=3", token, RUN_PAGE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PageView<AdminRunItem> page = response.getBody();
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(3);
        assertThat(page.total()).isEqualTo(before + 3);
        assertThat(page.items()).extracting(AdminRunItem::id).containsExactly(newest, middle, oldest);
        Map<UUID, AdminRunItem> byId = page.items().stream()
                .collect(Collectors.toMap(AdminRunItem::id, Function.identity()));
        assertThat(byId.get(oldest).ownerEmail()).isEqualTo(active.email());
        assertThat(byId.get(middle).ownerEmail()).isEqualTo(suspended.email());
        assertThat(byId.get(newest).ownerEmail()).isNull();
        assertThat(byId.get(middle).workflowName()).isEqualTo(workflow.name());
        assertThat(byId.get(middle).status()).isEqualTo("error");
        assertThat(byId.get(middle).errorMessage()).isEqualTo("Step 1 failed: boom");
        assertThat(byId.get(middle).startedAt()).isEqualTo(base.plusSeconds(60));
        assertThat(byId.get(middle).finishedAt()).isEqualTo(base.plusSeconds(61));
    }

    @Test
    void theStatusFilterReturnsOnlyMatchingRunsAndTheTotalCountsEveryMatch() {
        UserView admin = newUser("admin-filter");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(admin);
        long errorsBefore = total("/api/v1/admin/runs?size=1&status=error", token);
        Instant base = nextBase();
        seedRun(workflow.id(), admin.id(), "error", "old", base);
        UUID secondNewestError = seedRun(workflow.id(), admin.id(), "error", "mid", base.plusSeconds(10));
        seedRun(workflow.id(), admin.id(), "completed", null, base.plusSeconds(20));
        UUID newestError = seedRun(workflow.id(), admin.id(), "error", "new", base.plusSeconds(30));
        seedRun(workflow.id(), admin.id(), "completed", null, base.plusSeconds(40));

        PageView<AdminRunItem> page =
                getPage("/api/v1/admin/runs?page=0&size=2&status=error", token, RUN_PAGE).getBody();

        assertThat(page.items()).extracting(AdminRunItem::id).containsExactly(newestError, secondNewestError);
        assertThat(page.items()).allSatisfy(run -> assertThat(run.status()).isEqualTo("error"));
        assertThat(page.total()).isEqualTo(errorsBefore + 3);
    }

    @Test
    void aPageOfTwentyRunsResolvesEveryOwnerEmailInOneLookupWithinFourStatements() {
        UserView admin = newUser("admin-statements");
        WorkflowView workflow = newWorkflow(admin.id(), true, new NewWorkflowStep("only", "echo", null));
        Instant base = nextBase();
        List<UserView> owners = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UserView owner = userService.createUser(admin.id(), uniqueEmail("owner-" + i), "password123",
                    "Owner " + i, "MEMBER");
            owners.add(owner);
            seedRun(workflow.id(), owner.id(), "completed", null, base.plusSeconds(i));
        }
        String token = loginAs(admin);
        statements.clear();

        PageView<AdminRunItem> page = getPage("/api/v1/admin/runs?page=0&size=20", token, RUN_PAGE).getBody();

        assertThat(page.items()).hasSize(20);
        assertThat(page.items()).extracting(AdminRunItem::ownerEmail)
                .containsExactlyInAnyOrderElementsOf(owners.stream().map(UserView::email).toList());
        List<String> sql = statements.forRequest("/api/v1/admin/runs");
        assertThat(sql).as("statements for one page: %s", sql).hasSizeLessThanOrEqualTo(4);
        assertThat(sql.stream().filter(statement -> statement.contains("app_user")).count())
                .as("the caller's own check plus one owner-email lookup: %s", sql).isEqualTo(2);
        assertThat(sql.stream().filter(statement -> statement.contains("app_user") && statement.contains(" in ("))
                .toList()).singleElement().satisfies(lookup -> assertThat(lookup).doesNotContain("password_hash"));
    }

    @Test
    void theRunsPageCanBeServedByTheStartedAtIndex() {
        String plan = jdbc.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (ResultSet rows = statement.executeQuery("EXPLAIN SELECT wr.id, w.name FROM workflow_run wr "
                        + "JOIN workflow w ON w.id = wr.workflow_id "
                        + "ORDER BY wr.started_at DESC, wr.id DESC LIMIT 20")) {
                    StringBuilder lines = new StringBuilder();
                    while (rows.next()) {
                        lines.append(rows.getString(1)).append('\n');
                    }
                    return lines.toString();
                } finally {
                    statement.execute("RESET enable_seqscan");
                }
            }
        });

        assertThat(plan).contains("idx_workflow_run_started_at");
    }

    @Test
    void runsStartedAtTheSameInstantAreOrderedByIdDescending() {
        UserView admin = newUser("admin-tie");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant base = nextBase();
        List<UUID> ids = new ArrayList<>(List.of(
                seedRun(workflow.id(), admin.id(), "completed", null, base),
                seedRun(workflow.id(), admin.id(), "completed", null, base),
                seedRun(workflow.id(), admin.id(), "completed", null, base)));
        // Postgres orders uuids by their unsigned bytes, which is their hex text order; UUID.compareTo is signed.
        ids.sort(Comparator.comparing(UUID::toString).reversed());

        PageView<AdminRunItem> page = getPage("/api/v1/admin/runs?size=3", loginAs(admin), RUN_PAGE).getBody();

        assertThat(page.items()).extracting(AdminRunItem::id).containsExactlyElementsOf(ids);
    }

    @Test
    void aJobNotStartedYetSortsByWhenItsRunStarted() {
        UserView admin = newUser("admin-coalesce");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant base = nextBase();
        UUID olderRun = seedRun(workflow.id(), admin.id(), "running", null, base);
        UUID startedLater = seedStep(olderRun, 1, "probe", "running", base.plusSeconds(100), null,
                base.plusSeconds(3600), null, null, 1, 0, null);
        UUID newerRun = seedRun(workflow.id(), admin.id(), "running", null, base.plusSeconds(50));
        UUID pending = seedStep(newerRun, 2, "probe", "pending", null, null, null, null, null, 1, 0, null);
        UUID startedEarlier = seedStep(newerRun, 1, "probe", "running", base.plusSeconds(10), null,
                base.plusSeconds(3600), null, null, 1, 0, null);

        PageView<MediaJobItem> page = getPage("/api/v1/admin/media/jobs?size=3", loginAs(admin), JOB_PAGE).getBody();

        assertThat(page.items()).extracting(MediaJobItem::jobId).containsExactly(startedLater, pending, startedEarlier);
    }

    @Test
    void anAdminDemotedSinceTheTokenWasIssuedIsForbidden() {
        UserView owner = newUser("admin-demoting");
        UserView demoted = userService.createUser(owner.id(), uniqueEmail("demoted"), "password123", "Demoted",
                "ADMIN");
        String token = loginAs(demoted);
        userService.updateUser(owner.id(), demoted.id(), null, null, "MEMBER", null);

        assertForbiddenOnBothEndpoints(token);
    }

    @Test
    void aSuspendedAdminIsForbidden() {
        UserView owner = newUser("admin-suspending");
        UserView suspended = userService.createUser(owner.id(), uniqueEmail("suspended-admin"), "password123",
                "Suspended", "ADMIN");
        String token = loginAs(suspended);
        userService.updateUser(owner.id(), suspended.id(), null, null, null, "SUSPENDED");

        assertForbiddenOnBothEndpoints(token);
    }

    record CreateUserBody(String email, String password, String name, String role) {
    }

    @Test
    void aCustomerSupportReadsRunsButCannotWriteWorkflows() {
        UserView owner = newUser("admin-support-keys");
        UserView support = userService.createUser(owner.id(), uniqueEmail("support"), "password123", "Support",
                "CUSTOMER_SUPPORT");
        UserView operations = userService.createUser(owner.id(), uniqueEmail("operations"), "password123",
                "Operations", "OPERATION_MANAGER");
        String supportToken = loginAs(support);
        String operationsToken = loginAs(operations);
        String newcomer = uniqueEmail("newcomer");

        for (String path : List.of("/api/v1/admin/runs", "/api/v1/admin/media/jobs")) {
            assertThat(getPage(path, supportToken, ANY_PAGE).getStatusCode()).as("support " + path)
                    .isEqualTo(HttpStatus.OK);
            assertThat(getPage(path, operationsToken, ANY_PAGE).getStatusCode()).as("operations " + path)
                    .isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<ProblemView> write = restTemplate.exchange("/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(new CreateUserBody(newcomer, "password123", "Newcomer", "MEMBER"),
                        bearer(supportToken)), problem());

        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(write.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, newcomer))
                .isZero();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private void assertForbiddenOnBothEndpoints(String token) {
        for (String path : List.of("/api/v1/admin/runs", "/api/v1/admin/media/jobs")) {
            ResponseEntity<ProblemView> response = getPage(path, token, problem());

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getHeaders().getContentType()).as(path).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().items()).as(path).isNull();
        }
    }

    @Test
    void anOutOfRangeQueryIsA400ProblemNamingTheParameter() {
        String token = loginAs(newUser("admin-invalid"));

        ResponseEntity<ProblemView> tooLarge = getPage("/api/v1/admin/runs?size=500", token, problem());
        ResponseEntity<ProblemView> negativePage = getPage("/api/v1/admin/media/jobs?page=-1", token, problem());
        ResponseEntity<ProblemView> unknownStatus = getPage("/api/v1/admin/runs?status=lost", token, problem());

        assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLarge.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(tooLarge.getBody().errors()).containsKey("size");
        assertThat(negativePage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(negativePage.getBody().errors()).containsKey("page");
        assertThat(unknownStatus.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknownStatus.getBody().errors()).containsKey("status");
    }

    @Test
    void aPageWhoseOffsetWouldOverflowIsA400NotA500() {
        String token = loginAs(newUser("admin-huge-page"));

        for (String path : List.of("/api/v1/admin/runs", "/api/v1/admin/media/jobs")) {
            ResponseEntity<ProblemView> response = getPage(path + "?page=" + Integer.MAX_VALUE, token, problem());

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().errors()).as(path).containsKey("page");
        }
    }

    @Test
    void aNonNumericPageIsA400NamingThePage() {
        ResponseEntity<ProblemView> response =
                getPage("/api/v1/admin/media/jobs?page=abc", loginAs(newUser("admin-text-page")), problem());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().errors()).containsKey("page");
    }

    @Test
    void aMemberIsForbiddenWithAProblemDetailAndNoData() {
        UserView admin = newUser("admin-of-member");
        UserView member = userService.createUser(admin.id(), uniqueEmail("member"), "password123", "Member",
                "MEMBER");
        String token = loginAs(member);

        for (String path : List.of("/api/v1/admin/runs", "/api/v1/admin/media/jobs")) {
            ResponseEntity<ProblemView> response = getPage(path, token, problem());

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getHeaders().getContentType()).as(path).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().items()).as(path).isNull();
        }
    }

    @Test
    void anAnonymousCallerIsUnauthorizedWithAProblemDetail() {
        for (String path : List.of("/api/v1/admin/runs", "/api/v1/admin/media/jobs")) {
            ResponseEntity<ProblemView> response = getPage(path, null, problem());

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getContentType()).as(path).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @Test
    void mediaJobsListsRemoteStepsWithTheirDeliveryAndDeadlineFieldsAndNoInlineStep() {
        UserView admin = newUser("admin-jobs");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(admin);
        long jobsBefore = total("/api/v1/admin/media/jobs?size=1", token);
        long runningBefore = total("/api/v1/admin/media/jobs?size=1&status=running", token);
        Instant base = nextBase();
        UUID runningRun = seedRun(workflow.id(), admin.id(), "running", null, base);
        UUID pickedUp = seedStep(runningRun, 1, "probe", "running", base.plusSeconds(1), base.plusSeconds(2),
                base.plusSeconds(3600), base.plusSeconds(120), base.plusSeconds(300), 1, 2, null);
        UUID waiting = seedStep(runningRun, 2, "probe", "running", base.plusSeconds(4), null,
                base.plusSeconds(1800), null, null, 1, 0, null);
        UUID errorRun = seedRun(workflow.id(), admin.id(), "error", "probe failed", base.plusSeconds(5));
        UUID failed = seedStep(errorRun, 1, "probe", "error", base.plusSeconds(6), base.plusSeconds(7),
                base.plusSeconds(1806), base.plusSeconds(127), base.plusSeconds(306), 1, 0, "probe_failed");
        UUID inline = seedStep(errorRun, 2, "echo", "pending", null, null, null, null, null, 1, 0, null);

        PageView<MediaJobItem> all = getPage("/api/v1/admin/media/jobs?page=0&size=3", token, JOB_PAGE).getBody();
        PageView<MediaJobItem> running =
                getPage("/api/v1/admin/media/jobs?size=2&status=running", token, JOB_PAGE).getBody();

        assertThat(all.items()).extracting(MediaJobItem::jobId).containsExactly(failed, waiting, pickedUp);
        assertThat(all.items()).extracting(MediaJobItem::jobId).doesNotContain(inline);
        assertThat(all.total()).isEqualTo(jobsBefore + 3);
        MediaJobItem job = all.items().get(2);
        assertThat(job.runId()).isEqualTo(runningRun);
        assertThat(job.stepOrder()).isEqualTo(1);
        assertThat(job.capability()).isEqualTo("probe");
        assertThat(job.status()).isEqualTo("running");
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(job.deliveryCount()).isEqualTo(2);
        assertThat(job.pickedUpAt()).isEqualTo(base.plusSeconds(2));
        assertThat(job.queueDeadline()).isEqualTo(base.plusSeconds(3600));
        assertThat(job.leaseUntil()).isEqualTo(base.plusSeconds(120));
        assertThat(job.runDeadline()).isEqualTo(base.plusSeconds(300));
        assertThat(all.items().get(1).pickedUpAt()).isNull();
        assertThat(all.items().get(1).leaseUntil()).isNull();
        assertThat(all.items().get(0).errorCode()).isEqualTo("probe_failed");
        assertThat(running.items()).extracting(MediaJobItem::jobId).containsExactly(waiting, pickedUp);
        assertThat(running.total()).isEqualTo(runningBefore + 2);
    }

    @Test
    void everyRowsCreditsChargedIsNullWithCreditsOff() {
        UserView admin = newUser("admin-credits-off");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        UUID run = seedRun(workflow.id(), admin.id(), "completed", null, nextBase());

        PageView<AdminRunItem> page = getPage("/api/v1/admin/runs?size=5", loginAs(admin), RUN_PAGE).getBody();

        assertThat(page.items()).extracting(AdminRunItem::id).contains(run);
        assertThat(page.items()).allSatisfy(item -> assertThat(item.creditsCharged()).isNull());
    }

    @Test
    void workflowAndDateFiltersCombine() {
        UserView admin = newUser("admin-combine");
        WorkflowView w = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        WorkflowView v = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant day = nextBase();
        seedRun(w.id(), admin.id(), "completed", null, day);
        UUID inRange = seedRun(w.id(), admin.id(), "error", "boom", day.plus(Duration.ofDays(1)));
        seedRun(w.id(), admin.id(), "completed", null, day.plus(Duration.ofDays(2)));
        seedRun(v.id(), admin.id(), "error", "boom", day.plus(Duration.ofDays(1)).plusSeconds(3600));
        String token = loginAs(admin);

        PageView<AdminRunItem> page = getPage("/api/v1/admin/runs?workflowId=" + w.id() + "&from="
                + day.plus(Duration.ofDays(1)).minusSeconds(60) + "&to=" + day.plus(Duration.ofDays(2)), token,
                RUN_PAGE).getBody();
        PageView<AdminRunItem> edge = getPage("/api/v1/admin/runs?workflowId=" + w.id() + "&from="
                + day.plus(Duration.ofDays(1)) + "&to=" + day.plus(Duration.ofDays(2)), token, RUN_PAGE).getBody();
        PageView<AdminRunItem> excluded = getPage("/api/v1/admin/runs?workflowId=" + w.id() + "&from="
                + day.plus(Duration.ofDays(1)).plusSeconds(1) + "&to=" + day.plus(Duration.ofDays(2)), token,
                RUN_PAGE).getBody();

        assertThat(page.items()).extracting(AdminRunItem::id).containsExactly(inRange);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).workflowId()).isEqualTo(w.id());
        assertThat(page.items().get(0).ownerId()).isEqualTo(admin.id());
        assertThat(edge.items()).extracting(AdminRunItem::id).containsExactly(inRange);
        assertThat(excluded.total()).isZero();
    }

    @Test
    void aRangeWhoseFromIsNotBeforeToIsA400NamingFrom() {
        String token = loginAs(newUser("admin-bad-range"));

        ResponseEntity<ProblemView> reversed = getPage(
                "/api/v1/admin/runs?from=2026-11-03T00:00:00Z&to=2026-11-02T00:00:00Z", token, problem());
        ResponseEntity<String> equal = exchangeCsv(
                "/api/v1/admin/runs/export?from=2026-11-03T00:00:00Z&to=2026-11-03T00:00:00Z", token);
        ResponseEntity<ProblemView> badWorkflow = getPage("/api/v1/admin/runs?workflowId=abc", token, problem());
        ResponseEntity<ProblemView> badDate = getPage("/api/v1/admin/runs?from=yesterday", token, problem());

        assertThat(reversed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reversed.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(reversed.getBody().errors()).containsOnlyKeys("from");
        assertThat(equal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(equal.getBody()).contains("\"from\"");
        assertThat(badWorkflow.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badWorkflow.getBody().errors()).containsOnlyKeys("workflowId");
        assertThat(badDate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badDate.getBody().errors()).containsOnlyKeys("from");
    }

    @Test
    void theDetailListsStepsInOrderWithoutInputOrOutput() {
        UserView admin = newUser("admin-detail");
        UserView mai = userService.createUser(admin.id(), uniqueEmail("mai"), "password123", "Mai", "MEMBER");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant base = nextBase();
        UUID run = seedRun(workflow.id(), mai.id(), "error", "Step 2 failed", base);
        UUID first = seedStep(run, 1, "echo", "completed", base, null, null, null, null, 1, 0, null);
        UUID second = seedStep(run, 2, "probe", "error", base.plusSeconds(1), base.plusSeconds(2),
                base.plusSeconds(3600), base.plusSeconds(120), base.plusSeconds(300), 2, 3, "lease_expired");
        jdbc.update("UPDATE run_step SET input = 'secret-in', output = 'secret-out' WHERE id IN (?, ?)", first,
                second);
        jdbc.update("UPDATE workflow_run SET input = 'secret-run-input' WHERE id = ?", run);

        ResponseEntity<String> raw = get("/api/v1/admin/runs/" + run, loginAs(admin), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).doesNotContain("\"input\"", "\"output\"", "\"outputs\"", "secret-");
        AdminRunDetail detail = get("/api/v1/admin/runs/" + run, loginAs(admin), AdminRunDetail.class).getBody();
        assertThat(detail.ownerEmail()).isEqualTo(mai.email());
        assertThat(detail.workflowName()).isEqualTo(workflow.name());
        assertThat(detail.creditsCharged()).isNull();
        assertThat(detail.steps()).extracting(AdminRunDetail.Step::stepOrder).containsExactly(1, 2);
        AdminRunDetail.Step failed = detail.steps().get(1);
        assertThat(failed.status()).isEqualTo("error");
        assertThat(failed.errorCode()).isEqualTo("lease_expired");
        assertThat(failed.attempt()).isEqualTo(2);
        assertThat(failed.deliveryCount()).isEqualTo(3);
        assertThat(failed.pickedUpAt()).isEqualTo(base.plusSeconds(2));
        assertThat(detail.steps().get(0).mode()).isEqualTo("inline");
    }

    @Test
    void anUnknownRunDetailIs404() {
        ResponseEntity<ProblemView> response = getPage("/api/v1/admin/runs/" + UUID.randomUUID(),
                loginAs(newUser("admin-detail-404")), problem());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theExportHoldsTheFilteredRunsWithItsHeaderAndTotal() {
        UserView admin = newUser("admin-export");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant base = nextBase();
        seedRun(workflow.id(), admin.id(), "completed", null, base);
        UUID older = seedRun(workflow.id(), admin.id(), "error", "Lỗi bước 2,\nthử lại", base.plusSeconds(10));
        UUID newer = seedRun(workflow.id(), admin.id(), "error", "=cmd", base.plusSeconds(20));
        seedRun(workflow.id(), admin.id(), "error", "other workflow's twin", base.plusSeconds(30));
        jdbc.update("UPDATE workflow_run SET workflow_id = ? WHERE error_message = 'other workflow''s twin'",
                newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null)).id());

        ResponseEntity<String> response = exchangeCsv("/api/v1/admin/runs/export?status=error&workflowId="
                + workflow.id(), loginAs(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("2");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).matches("runs-\\d{8}\\.csv");
        String body = response.getBody();
        assertThat(body).startsWith("﻿\"Run ID\",\"Workflow ID\",\"Workflow\",\"Owner email\",\"Status\","
                + "\"Started at\",\"Finished at\",\"Duration (s)\",\"Credits charged\",\"Error\"\r\n");
        assertThat(body.indexOf(newer.toString())).isPositive().isLessThan(body.indexOf(older.toString()));
        assertThat(body).contains("\"'=cmd\"\r\n").contains("\"Lỗi bước 2,\nthử lại\"\r\n").endsWith("\r\n");
        assertThat(body).contains("\"1\",\"\",");
    }

    @Test
    void theExportStopsAtTheNewest1000AndCountsEveryMatch() {
        UserView admin = newUser("admin-export-cap");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant base = nextBase();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            rows.add(new Object[] {UUID.randomUUID(), workflow.id(), admin.id(), Timestamp.from(base.plusSeconds(i)),
                    Timestamp.from(base.plusSeconds(i + 1))});
        }
        jdbc.batchUpdate("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, "
                + "finished_at) VALUES (?, ?, ?, 'completed', 'seed', ?, ?)", rows);

        ResponseEntity<String> response = exchangeCsv("/api/v1/admin/runs/export?workflowId=" + workflow.id(),
                loginAs(admin));

        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("1001");
        String[] lines = response.getBody().split("\r\n");
        assertThat(lines).hasSize(1001);
        assertThat(lines[1]).contains(base.plusSeconds(1000).toString());
        assertThat(response.getBody()).doesNotContain(base.toString());
    }

    @Test
    void theWorkflowFilteredPageCanBeServedByTheWorkflowStartedIndex() {
        String plan = jdbc.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (ResultSet rows = statement.executeQuery("EXPLAIN SELECT wr.id FROM workflow_run wr "
                        + "WHERE wr.workflow_id = '" + UUID.randomUUID() + "' "
                        + "ORDER BY wr.started_at DESC, wr.id DESC LIMIT 20")) {
                    StringBuilder lines = new StringBuilder();
                    while (rows.next()) {
                        lines.append(rows.getString(1)).append('\n');
                    }
                    return lines.toString();
                } finally {
                    statement.execute("RESET enable_seqscan");
                }
            }
        });

        assertThat(plan).contains("idx_workflow_run_workflow_started");
    }

    @Test
    void anAdminStopsAnotherMembersRunningRunAndItsPendingStepsAreSkipped() {
        UserView admin = newUser("admin-stop");
        UserView mai = userService.createUser(admin.id(), uniqueEmail("mai-stop"), "password123", "Mai", "MEMBER");
        WorkflowView workflow = newWorkflow(mai.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant base = nextBase();
        UUID run = seedRun(workflow.id(), mai.id(), "running", null, base);
        seedStep(run, 1, "probe", "running", base, base.plusSeconds(1), base.plusSeconds(3600), base.plusSeconds(120),
                base.plusSeconds(300), 1, 1, null);
        seedStep(run, 2, "echo", "pending", null, null, null, null, null, 1, 0, null);
        seedStep(run, 3, "echo", "pending", null, null, null, null, null, 1, 0, null);

        ResponseEntity<String> response = post("/api/v1/admin/runs/" + run + "/cancel", loginAs(admin), null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("\"input\"", "\"output\"", "\"outputs\"");
        assertThat(response.getBody()).contains("\"status\":\"cancelling\"");
        AdminRunDetail detail = get("/api/v1/admin/runs/" + run, loginAs(admin), AdminRunDetail.class).getBody();
        assertThat(detail.status()).isEqualTo("cancelling");
        assertThat(detail.steps()).extracting(AdminRunDetail.Step::status)
                .containsExactly("running", "skipped", "skipped");
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_run WHERE id = ?", String.class, run))
                .isEqualTo("cancelling");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ?", Integer.class, run))
                .isZero();
    }

    @Test
    void theMemberSwitchOffStopsNeitherTheMemberNorTheStaffRoute() {
        UserView admin = newUser("admin-switch");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        UUID run = seedRun(workflow.id(), admin.id(), "waiting_approval", null, nextBase());
        String token = loginAs(admin);

        ResponseEntity<ProblemView> member = post("/api/v1/runs/" + run + "/cancel", token, null, ProblemView.class);
        ResponseEntity<AdminRunDetail> staff = post("/api/v1/admin/runs/" + run + "/cancel", token, null,
                AdminRunDetail.class);

        assertThat(member.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(staff.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(staff.getBody().status()).isEqualTo("cancelled");
    }

    @Test
    void anAdminStopOfAFinishedRunIs409UnknownIs404AndAStoppingRunStaysStopping() {
        UserView admin = newUser("admin-stop-edges");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant base = nextBase();
        UUID finished = seedRun(workflow.id(), admin.id(), "completed", null, base);
        UUID stopping = seedRun(workflow.id(), admin.id(), "cancelling", null, base.plusSeconds(1));
        String token = loginAs(admin);

        ResponseEntity<ProblemView> conflict = post("/api/v1/admin/runs/" + finished + "/cancel", token, null,
                ProblemView.class);
        ResponseEntity<ProblemView> unknown = post("/api/v1/admin/runs/" + UUID.randomUUID() + "/cancel", token,
                null, ProblemView.class);
        ResponseEntity<AdminRunDetail> again = post("/api/v1/admin/runs/" + stopping + "/cancel", token, null,
                AdminRunDetail.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_run WHERE id = ?", String.class, finished))
                .isEqualTo("completed");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().status()).isEqualTo("cancelling");
    }

    @Test
    void aCustomerSupportCannotStopOrExportRuns() {
        UserView admin = newUser("admin-support-run-keys");
        UserView support = userService.createUser(admin.id(), uniqueEmail("support-run"), "password123", "Support",
                "CUSTOMER_SUPPORT");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        UUID run = seedRun(workflow.id(), admin.id(), "running", null, nextBase());
        String token = loginAs(support);

        ResponseEntity<ProblemView> stop = post("/api/v1/admin/runs/" + run + "/cancel", token, null,
                ProblemView.class);
        ResponseEntity<String> export = exchangeCsv("/api/v1/admin/runs/export", token);

        assertThat(stop.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_run WHERE id = ?", String.class, run))
                .isEqualTo("running");
    }

    @Test
    void anOperationManagerStopsAndExports() {
        UserView admin = newUser("admin-ops-run-keys");
        UserView operations = userService.createUser(admin.id(), uniqueEmail("ops-run"), "password123",
                "Operations", "OPERATION_MANAGER");
        WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
        UUID run = seedRun(workflow.id(), admin.id(), "waiting_approval", null, nextBase());
        String token = loginAs(operations);

        ResponseEntity<AdminRunDetail> stop = post("/api/v1/admin/runs/" + run + "/cancel", token, null,
                AdminRunDetail.class);
        ResponseEntity<String> export = exchangeCsv("/api/v1/admin/runs/export?workflowId=" + workflow.id(), token);

        assertThat(stop.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getBody()).contains(run.toString());
    }

    @Nested
    @TestPropertySource(properties = "flowcortex.credits.enabled=true")
    @Import(StatementLogConfig.class)
    class WithCreditsOn {

        @Test
        void aPageOfTwentyRunsWithCreditsOnCostsAtMostFiveStatements() {
            UserView admin = newUser("admin-credits-statements");
            WorkflowView workflow = newWorkflow(admin.id(), true, new NewWorkflowStep("only", "echo", null));
            Instant base = nextBase();
            for (int i = 0; i < 20; i++) {
                UserView owner = userService.createUser(admin.id(), uniqueEmail("credits-owner-" + i), "password123",
                        "Owner " + i, "MEMBER");
                seedRun(workflow.id(), owner.id(), "completed", null, base.plusSeconds(i));
            }
            String token = loginAs(admin);
            statements.clear();

            ResponseEntity<PageView<AdminRunItem>> response =
                    getPage("/api/v1/admin/runs?page=0&size=20", token, RUN_PAGE);

            assertThat(response.getBody().items()).hasSize(20);
            List<String> sql = statements.forRequest("/api/v1/admin/runs");
            assertThat(sql).as("statements for one page: %s", sql).hasSizeLessThanOrEqualTo(5);
            assertThat(sql.stream().filter(statement -> statement.contains("app_user")).count())
                    .as("the caller's own check plus one owner-email lookup: %s", sql).isEqualTo(2);
            assertThat(sql.stream().filter(statement -> statement.contains("credit_ledger")).count())
                    .as("one grouped ledger read: %s", sql).isEqualTo(1);
        }

        @Test
        void aRunRowCarriesDebitsMinusRefunds() {
            UserView admin = newUser("admin-credits-row");
            WorkflowView workflow = newWorkflow(admin.id(), false, new NewWorkflowStep("only", "echo", null));
            Instant base = nextBase();
            UUID charged = seedRun(workflow.id(), admin.id(), "completed", null, base);
            UUID free = seedRun(workflow.id(), admin.id(), "completed", null, base.plusSeconds(10));
            seedLedger(admin.id(), charged, "debit", 30);
            seedLedger(admin.id(), charged, "refund", 10);

            String token = loginAs(admin);
            PageView<AdminRunItem> page = getPage("/api/v1/admin/runs?size=2", token, RUN_PAGE).getBody();
            AdminRunDetail detail = get("/api/v1/admin/runs/" + charged, token, AdminRunDetail.class).getBody();

            Map<UUID, AdminRunItem> byId = page.items().stream()
                    .collect(Collectors.toMap(AdminRunItem::id, Function.identity()));
            assertThat(byId.get(charged).creditsCharged()).isEqualTo(20L);
            assertThat(byId.get(free).creditsCharged()).isZero();
            assertThat(detail.creditsCharged()).isEqualTo(20L);
        }
    }

    private void seedLedger(UUID userId, UUID runId, String kind, long amount) {
        jdbc.update("INSERT INTO credit_ledger (id, user_id, run_id, kind, amount, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), userId, runId, kind, amount,
                Timestamp.from(Instant.now()));
    }

    record AdminRunDetail(
            UUID id, UUID workflowId, String workflowName, UUID ownerId, String ownerEmail, String status,
            String errorMessage, Instant startedAt, Instant finishedAt, Long creditsCharged, List<Step> steps) {

        record Step(
                int stepOrder, String type, String mode, String status, int attempt, int deliveryCount,
                Integer progressPercent, String errorCode, String errorMessage, Instant pickedUpAt,
                Instant startedAt, Instant finishedAt) {
        }
    }

    private ResponseEntity<String> exchangeCsv(String path, String token) {
        HttpHeaders headers = bearer(token);
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv")));
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static Instant nextBase() {
        return FAR_FUTURE.plus(Duration.ofDays(FIXTURE.getAndIncrement()));
    }

    private static ParameterizedTypeReference<ProblemView> problem() {
        return new ParameterizedTypeReference<>() {
        };
    }

    private <T> ResponseEntity<T> getPage(String path, String token, ParameterizedTypeReference<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), type);
    }

    private long total(String path, String token) {
        return getPage(path, token, ANY_PAGE).getBody().total();
    }

    private UUID seedRun(UUID workflowId, UUID ownerId, String status, String errorMessage, Instant startedAt) {
        UUID runId = UUID.randomUUID();
        Timestamp finishedAt = "running".equals(status) ? null : Timestamp.from(startedAt.plusSeconds(1));
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, error_message, started_at, "
                        + "finished_at) VALUES (?, ?, ?, ?, 'seed', ?, ?, ?)",
                runId, workflowId, ownerId, status, errorMessage, Timestamp.from(startedAt), finishedAt);
        return runId;
    }

    private UUID seedStep(UUID runId, int stepOrder, String type, String status, Instant startedAt,
            Instant pickedUpAt, Instant queueDeadline, Instant leaseUntil, Instant runDeadline, int attempt,
            int deliveryCount, String errorCode) {
        UUID stepId = UUID.randomUUID();
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, started_at, picked_up_at, "
                        + "queue_deadline, lease_until, run_deadline, attempt, delivery_count, error_code, "
                        + "error_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stepId, runId, stepOrder, type, status, timestamp(startedAt), timestamp(pickedUpAt),
                timestamp(queueDeadline), timestamp(leaseUntil), timestamp(runDeadline), attempt, deliveryCount,
                errorCode, errorCode == null ? null : "The file could not be read");
        return stepId;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** Records each JDBC statement with the path of the HTTP request it ran under. */
    static class StatementLog implements ObservationHandler<QueryContext> {

        private record Recorded(String path, String sql) {
        }

        private final Queue<Recorded> statements = new ConcurrentLinkedQueue<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof QueryContext;
        }

        @Override
        public void onStop(QueryContext context) {
            String path = requestPath(context);
            List<String> queries = context.getQueries() == null ? List.of() : context.getQueries();
            queries.forEach(sql -> statements.add(new Recorded(path, sql.toLowerCase(Locale.ROOT))));
        }

        void clear() {
            statements.clear();
        }

        List<String> forRequest(String path) {
            return statements.stream().filter(statement -> path.equals(statement.path()))
                    .map(Recorded::sql).toList();
        }

        private static String requestPath(Observation.ContextView context) {
            for (ObservationView parent = context.getParentObservation(); parent != null;
                    parent = parent.getContextView().getParentObservation()) {
                if (parent.getContextView() instanceof ServerRequestObservationContext request) {
                    return request.getCarrier().getRequestURI();
                }
            }
            return null;
        }
    }

    @TestConfiguration
    static class StatementLogConfig {

        @Bean
        StatementLog statementLog() {
            return new StatementLog();
        }
    }
}
