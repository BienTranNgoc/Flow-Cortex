package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.infrastructure.WorkflowModerationActionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Staff moderation over HTTP against Postgres: the report queue, the five actions on a report, the three direct
 * actions, the history, the keys each route needs, the audit entries and the transaction around an action. The
 * queue spans every report of the shared database, so each test names its workflows with a tag of its own, and
 * every workflow, report and history row a test makes is removed afterwards.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminWorkflowModerationIT extends AbstractWorkflowHttpIT {

    private static final String REPORTS = "/api/v1/admin/workflow-reports";
    private static final String DIRECT = "/api/v1/admin/moderation/workflows/";

    record Person(UUID id, String name, String email) {
    }

    record Reviewer(UUID id, String name) {
    }

    record WorkflowRef(
            UUID id, String name, String status, String visibility, String moderationStatus, boolean deleted,
            Person owner) {
    }

    record Row(
            UUID id, String reason, String details, String status, String resolution, Instant reviewedAt,
            Instant createdAt, Person reporter, Reviewer reviewer, WorkflowRef workflow) {
    }

    record Page(List<Row> items, int page, int size, long total) {
    }

    record Entry(
            UUID id, String action, String source, UUID reportId, UUID moderatorId, String moderatorName,
            String reason, String internalNote, String moderationStatus, Instant createdAt) {
    }

    record Detail(
            UUID id, String reason, String details, String status, String resolution, Instant reviewedAt,
            Instant createdAt, Person reporter, Reviewer reviewer, WorkflowRef workflow, String internalNote,
            List<Entry> history) {
    }

    record Outcome(UUID id, String moderationStatus) {
    }

    record Problem(int status, String detail, Map<String, String> errors) {
    }

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private WorkflowModerationActionRepository actionRepository;

    private final List<UUID> workflows = new ArrayList<>();

    private UserView admin;
    private UserView manager;
    private String managerToken;

    @BeforeEach
    void seedStaff() {
        admin = newUser("mod-admin");
        manager = staff("OPERATION_MANAGER");
        managerToken = loginAs(manager);
    }

    @AfterEach
    void removeWhatTheTestMade() {
        for (UUID id : workflows) {
            jdbc.update("DELETE FROM workflow_moderation_action WHERE workflow_id = ?", id);
            jdbc.update("DELETE FROM workflow_report WHERE workflow_id = ?", id);
            jdbc.update("UPDATE workflow SET moderation_status = 'visible' WHERE id = ?", id);
        }
        workflows.clear();
    }

    @Test
    void directHideSetsHiddenAndRecordsDirectEntry() {
        UUID id = workflow(newUser("mod-hide-owner"), tag());

        ResponseEntity<Outcome> response = post(DIRECT + id + "/hide", managerToken,
                Map.of("reason", "Phishing form"), Outcome.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new Outcome(id, "hidden"));
        assertThat(moderationStatus(id)).isEqualTo("hidden");
        assertThat(jdbc.queryForObject("SELECT visibility FROM workflow WHERE id = ?", String.class, id))
                .isEqualTo("public");
        List<Entry> history = history(id);
        assertThat(history).hasSize(1);
        Entry entry = history.get(0);
        assertThat(entry.action()).isEqualTo("hide");
        assertThat(entry.source()).isEqualTo("direct");
        assertThat(entry.reportId()).isNull();
        assertThat(entry.moderatorId()).isEqualTo(manager.id());
        assertThat(entry.moderatorName()).isEqualTo(manager.name());
        assertThat(entry.reason()).isEqualTo("Phishing form");
        assertThat(entry.moderationStatus()).isEqualTo("hidden");
    }

    @Test
    void reapplyingHideRecordsWithoutWrite() {
        UUID id = workflow(newUser("mod-reapply-owner"), tag());
        post(DIRECT + id + "/hide", managerToken, Map.of("reason", "Phishing form"), Outcome.class);
        String rowVersionAfterFirst = rowVersion(id);

        ResponseEntity<Outcome> second = post(DIRECT + id + "/hide", managerToken,
                Map.of("reason", "Second look, still phishing"), Outcome.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().moderationStatus()).isEqualTo("hidden");
        assertThat(rowVersion(id)).isEqualTo(rowVersionAfterFirst);
        assertThat(history(id)).extracting(Entry::action).containsExactly("hide", "hide");
        assertThat(history(id)).extracting(Entry::reason)
                .containsExactly("Second look, still phishing", "Phishing form");
    }

    @Test
    void directRemoveOfDeletedWorkflowIs404AndRecordsNothing() {
        UUID id = workflow(newUser("mod-deleted-owner"), tag());
        deleteWorkflow(id);

        ResponseEntity<Problem> response = post(DIRECT + id + "/remove", managerToken,
                Map.of("reason", "Gone"), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(moderationStatus(id)).isEqualTo("visible");
        assertThat(historyRows(id)).isZero();
    }

    @Test
    void unknownWorkflowIs404() {
        UUID id = UUID.randomUUID();

        assertThat(post(DIRECT + id + "/hide", managerToken, Map.of("reason", "Gone"), Problem.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(DIRECT + id + "/history", managerToken, Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void productVideoIs409() {
        ResponseEntity<Problem> response = post(DIRECT + ProductVideoWorkflow.ID + "/hide", managerToken,
                Map.of("reason", "Nope"), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(moderationStatus(ProductVideoWorkflow.ID)).isEqualTo("visible");
        assertThat(historyRows(ProductVideoWorkflow.ID)).isZero();
    }

    @Test
    void blankReasonIs422AndChangesNothing() {
        UUID id = workflow(newUser("mod-blank-owner"), tag());

        ResponseEntity<Problem> response = post(DIRECT + id + "/hide", managerToken, Map.of("reason", "   "),
                Problem.class);
        ResponseEntity<Problem> tooLong = post(DIRECT + id + "/hide", managerToken,
                Map.of("reason", "x".repeat(1001)), Problem.class);
        ResponseEntity<Problem> noteTooLong = post(DIRECT + id + "/hide", managerToken,
                Map.of("reason", "Phishing form", "internalNote", "n".repeat(1001)), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().errors()).containsKey("reason");
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(tooLong.getBody().errors()).containsKey("reason");
        assertThat(noteTooLong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noteTooLong.getBody().errors()).containsKey("internalNote");
        assertThat(moderationStatus(id)).isEqualTo("visible");
        assertThat(historyRows(id)).isZero();
    }

    @Test
    void reportHideSetsReportAndWorkflowAndRecordsReportEntry() {
        UserView owner = newUser("mod-rh-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-rh-lan"), Instant.now(), "spam", null);

        ResponseEntity<Detail> response = post(REPORTS + "/" + reportId + "/hide", managerToken,
                Map.of("reason", "Phishing form", "internalNote", "Checked with legal"), Detail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Detail detail = response.getBody();
        assertThat(detail.status()).isEqualTo("hidden");
        assertThat(detail.resolution()).isEqualTo("hide");
        assertThat(detail.reviewer().id()).isEqualTo(manager.id());
        assertThat(detail.reviewedAt()).isNotNull();
        assertThat(detail.internalNote()).isEqualTo("Checked with legal");
        assertThat(detail.workflow().moderationStatus()).isEqualTo("hidden");
        assertThat(moderationStatus(id)).isEqualTo("hidden");
        assertThat(detail.history()).hasSize(1);
        Entry entry = detail.history().get(0);
        assertThat(entry.action()).isEqualTo("hide");
        assertThat(entry.source()).isEqualTo("report");
        assertThat(entry.reportId()).isEqualTo(reportId);
        assertThat(entry.reason()).isEqualTo("Phishing form");
        assertThat(entry.internalNote()).isEqualTo("Checked with legal");
        assertThat(entry.moderationStatus()).isEqualTo("hidden");
    }

    @Test
    void restoreAfterHideResolvesReportAndKeepsVisibility() {
        UserView owner = newUser("mod-restore-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-restore-lan"), Instant.now(), "spam", null);
        post(REPORTS + "/" + reportId + "/hide", managerToken, Map.of("reason", "Phishing form"), Detail.class);

        ResponseEntity<Detail> response = post(REPORTS + "/" + reportId + "/restore", managerToken,
                Map.of("reason", "Owner fixed the form"), Detail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("resolved");
        assertThat(response.getBody().resolution()).isEqualTo("restore");
        assertThat(response.getBody().workflow().moderationStatus()).isEqualTo("visible");
        assertThat(moderationStatus(id)).isEqualTo("visible");
        assertThat(jdbc.queryForObject("SELECT visibility FROM workflow WHERE id = ?", String.class, id))
                .isEqualTo("public");
    }

    @Test
    void removeFromAReportSetsRemoved() {
        UserView owner = newUser("mod-remove-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-remove-lan"), Instant.now(), "scam", null);

        ResponseEntity<Detail> response = post(REPORTS + "/" + reportId + "/remove", managerToken,
                Map.of("reason", "Scam"), Detail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("removed");
        assertThat(moderationStatus(id)).isEqualTo("removed");
    }

    @Test
    void dismissOnDeletedWorkflowIsRecorded() {
        UserView owner = newUser("mod-dismiss-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-dismiss-lan"), Instant.now(), "spam", null);
        deleteWorkflow(id);

        ResponseEntity<Detail> response = post(REPORTS + "/" + reportId + "/dismiss", managerToken, null,
                Detail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("dismissed");
        assertThat(response.getBody().workflow().deleted()).isTrue();
        assertThat(moderationStatus(id)).isEqualTo("visible");
        List<Entry> history = history(id);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).action()).isEqualTo("dismiss");
        assertThat(history.get(0).reason()).isNull();
        assertThat(history.get(0).moderationStatus()).isEqualTo("visible");
    }

    @Test
    void hideOnDeletedWorkflowIs404AndChangesNothing() {
        UserView owner = newUser("mod-hide-deleted-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-hide-deleted-lan"), Instant.now(), "spam", "before");
        deleteWorkflow(id);

        ResponseEntity<Problem> response = post(REPORTS + "/" + reportId + "/hide", managerToken,
                Map.of("reason", "Phishing form", "internalNote", "after"), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reportState(reportId)).containsEntry("status", "pending").containsEntry("internal_note", "before");
        assertThat(reportState(reportId).get("reviewer_id")).isNull();
        assertThat(historyRows(id)).isZero();
    }

    @Test
    void noteKeptWhenNoneSent() {
        UserView owner = newUser("mod-note-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-note-lan"), Instant.now(), "spam", null);
        post(REPORTS + "/" + reportId + "/dismiss", managerToken, Map.of("internalNote", "Checked with legal"),
                Detail.class);

        ResponseEntity<Detail> response = post(REPORTS + "/" + reportId + "/resolve", managerToken, Map.of(),
                Detail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("resolved");
        assertThat(response.getBody().internalNote()).isEqualTo("Checked with legal");
    }

    @Test
    void queueIsNewestFirstAndFiltersByStatus() {
        UserView owner = newUser("mod-queue-owner");
        UserView lan = member(owner, "mod-queue-lan");
        String tag = tag();
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        UUID oldest = report(workflow(owner, tag + " a"), lan, base, "spam", null);
        UUID tieOne = report(workflow(owner, tag + " b"), lan, base.plusSeconds(60), "spam", null);
        UUID tieTwo = report(workflow(owner, tag + " c"), lan, base.plusSeconds(60), "spam", null);
        UUID dismissed = report(workflow(owner, tag + " d"), lan, base.plusSeconds(120), "spam", null);
        post(REPORTS + "/" + dismissed + "/dismiss", managerToken, null, Detail.class);
        List<UUID> tied = List.of(tieOne, tieTwo).stream()
                .sorted(Comparator.comparing(UUID::toString).reversed()).toList();

        Page pending = get(REPORTS + "?status=pending&size=100&q=" + tag, managerToken, Page.class).getBody();
        Page firstOfTwo = get(REPORTS + "?status=pending&page=0&size=1&q=" + tag, managerToken, Page.class).getBody();
        Page secondOfTwo = get(REPORTS + "?status=pending&page=1&size=1&q=" + tag, managerToken, Page.class).getBody();
        Page onlyDismissed = get(REPORTS + "?status=dismissed&q=" + tag, managerToken, Page.class).getBody();
        Page everything = get(REPORTS + "?size=100&q=" + tag, managerToken, Page.class).getBody();

        assertThat(pending.total()).isEqualTo(3);
        assertThat(pending.items()).extracting(Row::id).containsExactly(tied.get(0), tied.get(1), oldest);
        assertThat(firstOfTwo.items()).extracting(Row::id).containsExactly(tied.get(0));
        assertThat(firstOfTwo.total()).isEqualTo(3);
        assertThat(firstOfTwo.size()).isEqualTo(1);
        assertThat(secondOfTwo.items()).extracting(Row::id).containsExactly(tied.get(1));
        assertThat(onlyDismissed.items()).extracting(Row::id).containsExactly(dismissed);
        assertThat(everything.total()).isEqualTo(4);
        assertThat(everything.items().get(0).id()).isEqualTo(dismissed);
    }

    @Test
    void nameFilterIsLiteral() {
        UserView owner = newUser("mod-literal-owner");
        UserView lan = member(owner, "mod-literal-lan");
        String tag = tag();
        UUID literal = report(workflow(owner, tag + " Sale 50% off"), lan, Instant.now(), "spam", null);
        report(workflow(owner, tag + " Sale 500 items"), lan, Instant.now(), "spam", null);

        Page page = get(REPORTS + "?size=100&q=" + tag + " Sale 50%", managerToken, Page.class).getBody();
        Page otherCase = get(REPORTS + "?size=100&q=" + tag.toUpperCase() + " SALE", managerToken, Page.class)
                .getBody();

        assertThat(page.items()).extracting(Row::id).containsExactly(literal);
        assertThat(otherCase.total()).isEqualTo(2);
    }

    @Test
    void queueRowCarriesReporterReviewerAndOwner() {
        UserView owner = newUser("mod-row-owner");
        UserView lan = member(owner, "mod-row-lan");
        String tag = tag();
        UUID pendingId = report(workflow(owner, tag + " pending"), lan, Instant.now().minusSeconds(10), "scam",
                "It asks for a password");
        UUID reviewedId = report(workflow(owner, tag + " reviewed"), lan, Instant.now(), "spam", null);
        post(REPORTS + "/" + reviewedId + "/dismiss", managerToken, null, Detail.class);

        Page page = get(REPORTS + "?size=100&q=" + tag, managerToken, Page.class).getBody();

        Row pending = page.items().stream().filter(row -> row.id().equals(pendingId)).findFirst().orElseThrow();
        assertThat(pending.reason()).isEqualTo("scam");
        assertThat(pending.details()).isEqualTo("It asks for a password");
        assertThat(pending.status()).isEqualTo("pending");
        assertThat(pending.resolution()).isNull();
        assertThat(pending.reviewedAt()).isNull();
        assertThat(pending.reviewer()).isNull();
        assertThat(pending.createdAt()).isNotNull();
        assertThat(pending.reporter()).isEqualTo(new Person(lan.id(), lan.name(), lan.email()));
        assertThat(pending.workflow().id()).isNotNull();
        assertThat(pending.workflow().name()).isEqualTo(tag + " pending");
        assertThat(pending.workflow().status()).isEqualTo("published");
        assertThat(pending.workflow().visibility()).isEqualTo("public");
        assertThat(pending.workflow().moderationStatus()).isEqualTo("visible");
        assertThat(pending.workflow().deleted()).isFalse();
        assertThat(pending.workflow().owner()).isEqualTo(new Person(owner.id(), owner.name(), owner.email()));
        Row reviewed = page.items().stream().filter(row -> row.id().equals(reviewedId)).findFirst().orElseThrow();
        assertThat(reviewed.status()).isEqualTo("dismissed");
        assertThat(reviewed.resolution()).isEqualTo("dismiss");
        assertThat(reviewed.reviewedAt()).isNotNull();
        assertThat(reviewed.reviewer()).isEqualTo(new Reviewer(manager.id(), manager.name()));
    }

    @Test
    void badParametersAre400NamingThem() {
        ResponseEntity<Problem> status = get(REPORTS + "?status=closed", managerToken, Problem.class);
        ResponseEntity<Problem> size = get(REPORTS + "?size=500", managerToken, Problem.class);
        ResponseEntity<Problem> page = get(REPORTS + "?page=-1", managerToken, Problem.class);
        ResponseEntity<Problem> query = get(REPORTS + "?q=" + "a".repeat(256), managerToken, Problem.class);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status.getBody().errors()).containsKey("status");
        assertThat(size.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(size.getBody().errors()).containsKey("size");
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(page.getBody().errors()).containsKey("page");
        assertThat(query.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(query.getBody().errors()).containsKey("q");
    }

    @Test
    void unknownReportIs404() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Problem> detail = get(REPORTS + "/" + id, managerToken, Problem.class);
        ResponseEntity<Problem> action = post(REPORTS + "/" + id + "/hide", managerToken,
                Map.of("reason", "Phishing form"), Problem.class);

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(action.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void historyMixesBothSourcesNewestFirstAndIncludesDeleted() {
        UserView owner = newUser("mod-history-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-history-lan"), Instant.now(), "spam", null);
        post(REPORTS + "/" + reportId + "/hide", managerToken, Map.of("reason", "Phishing form"), Detail.class);
        post(DIRECT + id + "/restore", managerToken, Map.of("reason", "Owner fixed the form"), Outcome.class);

        List<Entry> history = history(id);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).action()).isEqualTo("restore");
        assertThat(history.get(0).source()).isEqualTo("direct");
        assertThat(history.get(0).reportId()).isNull();
        assertThat(history.get(0).moderationStatus()).isEqualTo("visible");
        assertThat(history.get(1).action()).isEqualTo("hide");
        assertThat(history.get(1).source()).isEqualTo("report");
        assertThat(history.get(1).reportId()).isEqualTo(reportId);
        assertThat(history.get(1).moderationStatus()).isEqualTo("hidden");
        Detail detail = get(REPORTS + "/" + reportId, managerToken, Detail.class).getBody();
        assertThat(detail.history()).extracting(Entry::id).containsExactlyElementsOf(
                history.stream().map(Entry::id).toList());

        deleteWorkflow(id);

        assertThat(history(id)).hasSize(2);
    }

    @Test
    void supportReadsButCannotHide() {
        UserView owner = newUser("mod-support-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-support-lan"), Instant.now(), "spam", null);
        String support = loginAs(staff("CUSTOMER_SUPPORT"));

        assertThat(get(REPORTS + "?size=100&q=" + tagOf(id), support, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get(REPORTS + "/" + reportId, support, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(DIRECT + id + "/history", support, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(REPORTS + "/" + reportId + "/hide", support, Map.of("reason", "Phishing form"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(REPORTS + "/" + reportId + "/dismiss", support, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(DIRECT + id + "/hide", support, Map.of("reason", "Phishing form"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(moderationStatus(id)).isEqualTo("visible");
        assertThat(reportState(reportId)).containsEntry("status", "pending");
        assertThat(historyRows(id)).isZero();
    }

    @Test
    void platformOperatorHidesDirectlyButCannotList() {
        UUID id = workflow(newUser("mod-operator-owner"), tag());
        String operator = loginAs(staff("PLATFORM_OPERATOR"));

        ResponseEntity<Outcome> hide = post(DIRECT + id + "/hide", operator, Map.of("reason", "Phishing form"),
                Outcome.class);

        assertThat(hide.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(REPORTS, operator, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(DIRECT + id + "/history", operator, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moderationStatus(id)).isEqualTo("hidden");
    }

    @Test
    void reportHideNeedsBothKeys() {
        UserView owner = newUser("mod-both-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-both-lan"), Instant.now(), "spam", null);
        String resolver = loginAs(staff("CUSTOMER_SUPPORT"));
        jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES ('CUSTOMER_SUPPORT', "
                + "'moderation.report.resolve') ON CONFLICT DO NOTHING");
        try {
            ResponseEntity<String> hide = post(REPORTS + "/" + reportId + "/hide", resolver,
                    Map.of("reason", "Phishing form"), String.class);
            ResponseEntity<Detail> dismiss = post(REPORTS + "/" + reportId + "/dismiss", resolver, null,
                    Detail.class);

            assertThat(hide.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(dismiss.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(moderationStatus(id)).isEqualTo("visible");
        } finally {
            jdbc.update("DELETE FROM role_permission WHERE role_code = 'CUSTOMER_SUPPORT' "
                    + "AND permission_key = 'moderation.report.resolve'");
        }
    }

    @Test
    void memberTokenIs403() {
        UserView owner = newUser("mod-member-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-member-lan"), Instant.now(), "spam", null);
        String member = loginAs(member(owner, "mod-member-caller"));

        assertThat(get(REPORTS, member, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(REPORTS + "/" + reportId, member, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(REPORTS + "/" + reportId + "/dismiss", member, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(DIRECT + id + "/hide", member, Map.of("reason", "Phishing form"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(DIRECT + id + "/history", member, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(moderationStatus(id)).isEqualTo("visible");
    }

    @Test
    void concurrentReportHideAndDirectRemoveAreBothRecordedInCommitOrder() throws Exception {
        UserView owner = newUser("mod-race-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-race-lan"), Instant.now(), "spam", null);
        String other = loginAs(staff("OPERATION_MANAGER"));

        List<ResponseEntity<String>> results = inParallel(List.of(
                () -> post(REPORTS + "/" + reportId + "/hide", managerToken, Map.of("reason", "Phishing form"),
                        String.class),
                () -> post(DIRECT + id + "/remove", other, Map.of("reason", "Taken down"), String.class)));

        assertThat(results).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.OK);
        List<Entry> history = history(id);
        assertThat(history).extracting(Entry::action).containsExactlyInAnyOrder("hide", "remove");
        assertThat(history.get(0).createdAt()).isAfterOrEqualTo(history.get(1).createdAt());
        assertThat(moderationStatus(id)).isEqualTo(history.get(0).moderationStatus());
        assertThat(reportState(reportId)).containsEntry("status", "hidden");
    }

    @Test
    void historyFailureRollsBackStatusAndReport() {
        UserView owner = newUser("mod-rollback-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-rollback-lan"), Instant.now(), "spam", "before");
        doThrow(new IllegalStateException("history is down")).when(actionRepository).save(any());

        ResponseEntity<String> response = post(REPORTS + "/" + reportId + "/hide", managerToken,
                Map.of("reason", "Phishing form", "internalNote", "after"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(moderationStatus(id)).isEqualTo("visible");
        assertThat(reportState(reportId)).containsEntry("status", "pending").containsEntry("internal_note", "before");
        assertThat(reportState(reportId).get("reviewer_id")).isNull();
        assertThat(reportState(reportId).get("resolution")).isNull();
        assertThat(historyRows(id)).isZero();
    }

    @Test
    void auditEntryHasNoReasonText() {
        UserView owner = newUser("mod-audit-owner");
        UUID id = workflow(owner, tag());
        UUID reportId = report(id, member(owner, "mod-audit-lan"), Instant.now(), "spam", null);
        String reason = "Phishing form " + UUID.randomUUID();
        String note = "Private note " + UUID.randomUUID();

        post(DIRECT + id + "/hide", managerToken, Map.of("reason", reason, "internalNote", note), Outcome.class);
        post(REPORTS + "/" + reportId + "/dismiss", managerToken, Map.of("reason", reason, "internalNote", note),
                Detail.class);

        List<Map<String, Object>> hide = audit("workflow.hide");
        List<Map<String, Object>> dismiss = audit("workflow_report.dismiss");
        assertThat(hide).hasSize(1);
        assertThat(hide.get(0).get("resource_id")).isEqualTo(id.toString());
        assertThat(hide.get(0).get("entry").toString()).doesNotContain(reason).doesNotContain(note);
        assertThat(dismiss).hasSize(1);
        assertThat(dismiss.get(0).get("resource_id")).isEqualTo(reportId.toString());
        assertThat(dismiss.get(0).get("entry").toString()).doesNotContain(reason).doesNotContain(note);
    }

    private List<Map<String, Object>> audit(String action) {
        return jdbc.queryForList("SELECT resource_id, to_jsonb(e)::text AS entry FROM admin_audit_event e "
                + "WHERE actor_user_id = ? AND action = ?", manager.id(), action);
    }

    private UserView staff(String role) {
        return userService.createUser(admin.id(), uniqueEmail("mod-" + role.toLowerCase().replace('_', '-')),
                "password123", "Staff " + role, role);
    }

    private UserView member(UserView someAdmin, String prefix) {
        return userService.createUser(someAdmin.id(), uniqueEmail(prefix), "password123", prefix, "MEMBER");
    }

    private static String tag() {
        return "mod" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String tagOf(UUID workflowId) {
        return jdbc.queryForObject("SELECT name FROM workflow WHERE id = ?", String.class, workflowId);
    }

    /** A public published workflow of {@code owner} under {@code name}, removed with its reports after the test. */
    private UUID workflow(UserView owner, String name) {
        UUID id = newWorkflow(owner.id(), true, new NewWorkflowStep("only", "echo", null)).id();
        jdbc.update("UPDATE workflow SET name = ? WHERE id = ?", name, id);
        workflows.add(id);
        return id;
    }

    private UUID report(UUID workflowId, UserView reporter, Instant createdAt, String reason, String internalNote) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_report (id, workflow_id, reporter_id, reason, details, internal_note,"
                + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", id, workflowId, reporter.id(), reason,
                "spam".equals(reason) ? null : "It asks for a password", internalNote, Timestamp.from(createdAt));
        return id;
    }

    private void deleteWorkflow(UUID id) {
        jdbc.update("UPDATE workflow SET deleted_at = now() WHERE id = ?", id);
    }

    private String moderationStatus(UUID workflowId) {
        return jdbc.queryForObject("SELECT moderation_status FROM workflow WHERE id = ?", String.class, workflowId);
    }

    /** Changes with every UPDATE of the row, even one that writes the same values. */
    private String rowVersion(UUID workflowId) {
        return jdbc.queryForObject("SELECT xmin::text FROM workflow WHERE id = ?", String.class, workflowId);
    }

    private long historyRows(UUID workflowId) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_moderation_action WHERE workflow_id = ?",
                Long.class, workflowId);
    }

    private Map<String, Object> reportState(UUID reportId) {
        return jdbc.queryForMap("SELECT status, resolution, reviewer_id, internal_note FROM workflow_report "
                + "WHERE id = ?", reportId);
    }

    private List<Entry> history(UUID workflowId) {
        return List.of(get(DIRECT + workflowId + "/history", managerToken, Entry[].class).getBody());
    }

    private <T> List<ResponseEntity<T>> inParallel(List<Callable<ResponseEntity<T>>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResponseEntity<T>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<T>> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ResponseEntity<T>> results = new ArrayList<>();
            for (Future<ResponseEntity<T>> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
