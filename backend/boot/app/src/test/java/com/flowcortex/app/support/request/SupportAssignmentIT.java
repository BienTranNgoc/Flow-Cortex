package com.flowcortex.app.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Assignment, bulk assignment and the queue of the admin inbox over HTTP: the pick-up transition, who may be
 * assigned and who may assign, two writers racing for one request, the filters, search, sort and the counts.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.support.mail.interval=PT24H",
        "flowcortex.support.retention.interval=PT24H"})
@AutoConfigureTestRestTemplate
class SupportAssignmentIT extends AbstractPostgresBootTest {

    private static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");
    private static final String PASSWORD = "password123";
    private static final String ADMIN_REQUESTS = "/api/v1/admin/support/requests";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UserView superAdmin;
    private UserView minh;
    private UserView linh;
    private UserView hoa;
    private UserView binh;
    private UserView tam;
    private String minhToken;
    private String linhToken;
    private String hoaToken;
    private String memberToken;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record CreateRequest(String type, String subject, String description, List<UUID> uploadIds) {
    }

    record AssigneeBody(UUID assigneeId) {
    }

    record BulkBody(List<Long> numbers, UUID assigneeId) {
    }

    record AssigneeView(UUID id, String email, boolean deleted) {
    }

    record DetailView(long number, String status, AssigneeView assignee) {
    }

    record SummaryView(long number, String status, AssigneeView assignee) {
    }

    record PageView(List<SummaryView> items, long total) {
    }

    record Failure(long number, String reason) {
    }

    record BulkResult(List<Long> assigned, List<Long> unchanged, List<Failure> failed) {
    }

    record Count(String value, long count) {
    }

    record AssigneeCount(UUID id, String email, long count) {
    }

    record AssigneeFacet(long unassigned, long mine, List<AssigneeCount> people) {
    }

    record Facets(List<Count> status, List<Count> type, List<Count> created, AssigneeFacet assignee) {
    }

    record Problem(int status, URI type, String detail, Map<String, String> errors) {
    }

    @BeforeEach
    void fixture() {
        jdbcTemplate.update("DELETE FROM support_request");
        String run = UUID.randomUUID().toString().substring(0, 8);
        superAdmin = userService.createInitialAdmin("assign-admin-" + run + "@nodi.test", PASSWORD, "Admin");
        minh = userService.createUser(superAdmin.id(), "minh-" + run + "@nodi.test", PASSWORD, "Minh",
                "OPERATION_MANAGER");
        linh = userService.createUser(superAdmin.id(), "linh-" + run + "@nodi.test", PASSWORD, "Linh",
                "CUSTOMER_SUPPORT");
        hoa = userService.createUser(superAdmin.id(), "hoa-" + run + "@nodi.test", PASSWORD, "Hoa",
                "PLATFORM_OPERATOR");
        binh = userService.createUser(superAdmin.id(), "binh-" + run + "@nodi.test", PASSWORD, "Binh",
                "CONTENT_EDITOR");
        tam = userService.createUser(superAdmin.id(), "tam-" + run + "@nodi.test", PASSWORD, "Tam",
                "CUSTOMER_SUPPORT");
        jdbcTemplate.update("UPDATE app_user SET admin_disabled_at = now(), admin_disabled_by = ? WHERE id = ?",
                superAdmin.id(), tam.id());
        UserView member = userService.createUser(superAdmin.id(), "member-" + run + "@nodi.test", PASSWORD,
                "Member", "MEMBER");
        minhToken = login(minh.email());
        linhToken = login(linh.email());
        hoaToken = login(hoa.email());
        memberToken = login(member.email());
    }

    @Test
    void assigningAnOpenRequestKeepsItsStatusAndLastActivity() {
        long number = file("credit", "Cần hỗ trợ");
        jdbcTemplate.update("UPDATE support_request SET status = 'in_progress', "
                + "last_activity_at = now() - interval '2 days' WHERE number = ?", number);
        Timestamp before = lastActivity(number);

        ResponseEntity<DetailView> response = assign(minhToken, number, linh.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("in_progress");
        assertThat(response.getBody().assignee().email()).isEqualTo(linh.email());
        assertThat(lastActivity(number)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT assigned_at IS NOT NULL FROM support_request WHERE number = ?",
                Boolean.class, number)).isTrue();
    }

    @Test
    void settingTheSameAssigneeAgainChangesNothing() {
        long number = file("credit", "Cần hỗ trợ");
        assign(minhToken, number, linh.id());
        Timestamp assignedAt = jdbcTemplate.queryForObject("SELECT assigned_at FROM support_request WHERE number = ?",
                Timestamp.class, number);

        ResponseEntity<DetailView> again = assign(minhToken, number, linh.id());

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT assigned_at FROM support_request WHERE number = ?",
                Timestamp.class, number)).isEqualTo(assignedAt);
    }

    @Test
    void unassigningClearsTheAssigneeAndTheAssignmentTimeAndKeepsTheStatus() {
        long number = file("credit", "Cần hỗ trợ");
        assign(minhToken, number, linh.id());

        ResponseEntity<DetailView> response = assign(minhToken, number, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().assignee()).isNull();
        assertThat(response.getBody().status()).isEqualTo("in_progress");
        assertThat(jdbcTemplate.queryForMap("SELECT assignee_id, assigned_at FROM support_request WHERE number = ?",
                number)).containsEntry("assignee_id", null).containsEntry("assigned_at", null);
    }

    @Test
    void assigningANewRequestPicksItUpAndMovesItsActivity() {
        long number = file("credit", "Cần hỗ trợ");
        jdbcTemplate.update("UPDATE support_request SET last_activity_at = now() - interval '2 days' "
                + "WHERE number = ?", number);
        Timestamp before = lastActivity(number);

        ResponseEntity<DetailView> response = assign(linhToken, number, linh.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("in_progress");
        assertThat(lastActivity(number)).isAfter(before);
    }

    @Test
    void aContentEditorAndADeactivatedAgentAreRefusedAndTheRowStaysAsItWas() {
        long number = file("credit", "Cần hỗ trợ");

        for (UserView refused : List.of(binh, tam)) {
            ResponseEntity<Problem> response = restTemplate.exchange(ADMIN_REQUESTS + "/" + number + "/assignee",
                    HttpMethod.PUT, new HttpEntity<>(new AssigneeBody(refused.id()), bearer(minhToken)),
                    Problem.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
            assertThat(response.getBody().errors()).containsKey("assigneeId");
        }
        assertThat(jdbcTemplate.queryForMap("SELECT status, assignee_id FROM support_request WHERE number = ?",
                number)).containsEntry("status", "new").containsEntry("assignee_id", null);
    }

    @Test
    void aClosedRequestRefusesAssignment() {
        long number = file("credit", "Cần hỗ trợ");
        jdbcTemplate.update("UPDATE support_request SET status = 'closed', closed_reason = 'admin' "
                + "WHERE number = ?", number);

        ResponseEntity<Problem> response = restTemplate.exchange(ADMIN_REQUESTS + "/" + number + "/assignee",
                HttpMethod.PUT, new HttpEntity<>(new AssigneeBody(linh.id()), bearer(minhToken)), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("request-closed"));
    }

    @Test
    void twoPickUpsOfOneNewRequestAtOnceStoreExactlyOne() throws Exception {
        long number = file("credit", "Cần hỗ trợ");
        AtomicReference<CompletableFuture<ResponseEntity<Map<String, Object>>>> byLinh = new AtomicReference<>();
        AtomicReference<CompletableFuture<ResponseEntity<Map<String, Object>>>> byMinh = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("SELECT id FROM support_request WHERE number = ? FOR UPDATE", UUID.class,
                    number);
            byLinh.set(CompletableFuture.supplyAsync(() -> pickUp(linhToken, number, linh.id())));
            byMinh.set(CompletableFuture.supplyAsync(() -> pickUp(minhToken, number, minh.id())));
            awaitBlockedUpdates(2);
            status.setRollbackOnly();
        });
        List<ResponseEntity<Map<String, Object>>> outcomes = List.of(byLinh.get().get(10, TimeUnit.SECONDS),
                byMinh.get().get(10, TimeUnit.SECONDS));

        assertThat(outcomes).filteredOn(r -> r.getStatusCode() == HttpStatus.OK).hasSize(1);
        assertThat(outcomes).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT).singleElement()
                .satisfies(r -> assertThat(r.getBody().get("type"))
                        .isEqualTo(PROBLEMS.resolve("request-state-changed").toString()));
        assertThat(jdbcTemplate.queryForMap("SELECT status, assignee_id FROM support_request WHERE number = ?",
                number)).containsEntry("status", "in_progress")
                .extractingByKey("assignee_id").isIn(linh.id(), minh.id());
    }

    @Test
    void aPlatformOperatorCannotAssignOrBulkAssign() {
        long number = file("credit", "Cần hỗ trợ");

        ResponseEntity<Problem> single = restTemplate.exchange(ADMIN_REQUESTS + "/" + number + "/assignee",
                HttpMethod.PUT, new HttpEntity<>(new AssigneeBody(linh.id()), bearer(hoaToken)), Problem.class);
        ResponseEntity<Problem> bulk = restTemplate.exchange(ADMIN_REQUESTS + "/bulk-assign", HttpMethod.POST,
                new HttpEntity<>(new BulkBody(List.of(number), linh.id()), bearer(hoaToken)), Problem.class);

        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bulk.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject("SELECT assignee_id FROM support_request WHERE number = ?",
                UUID.class, number)).isNull();
    }

    @Test
    void aMixedBatchAssignsKeepsRefusesAndReportsEachNumber() {
        long fresh = file("credit", "Yêu cầu mới");
        long mine = file("credit", "Đã giao");
        long closed = file("credit", "Đã đóng");
        assign(minhToken, mine, linh.id());
        jdbcTemplate.update("UPDATE support_request SET status = 'closed', closed_reason = 'admin' "
                + "WHERE number = ?", closed);

        ResponseEntity<BulkResult> response = restTemplate.exchange(ADMIN_REQUESTS + "/bulk-assign",
                HttpMethod.POST, new HttpEntity<>(new BulkBody(List.of(fresh, mine, closed, 999_999_999L),
                        linh.id()), bearer(minhToken)), BulkResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BulkResult result = response.getBody();
        assertThat(result.assigned()).containsExactly(fresh);
        assertThat(result.unchanged()).containsExactly(mine);
        assertThat(result.failed()).extracting(Failure::number, Failure::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(closed, "closed"),
                        org.assertj.core.groups.Tuple.tuple(999_999_999L, "not_found"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM support_request WHERE number = ?", String.class,
                fresh)).isEqualTo("in_progress");
    }

    @Test
    void aBatchOfMoreThanAHundredNumbersChangesNothing() {
        long number = file("credit", "Cần hỗ trợ");
        List<Long> numbers = new java.util.ArrayList<>(List.of(number));
        for (long extra = 1; extra <= 100; extra++) {
            numbers.add(extra + 1_000_000_000L);
        }

        ResponseEntity<Problem> response = restTemplate.exchange(ADMIN_REQUESTS + "/bulk-assign", HttpMethod.POST,
                new HttpEntity<>(new BulkBody(numbers, linh.id()), bearer(minhToken)), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors()).containsKey("numbers");
        assertThat(jdbcTemplate.queryForObject("SELECT assignee_id FROM support_request WHERE number = ?",
                UUID.class, number)).isNull();
    }

    @Test
    void myOpenQueueHoldsOnlyMyInProgressRequests() {
        long mineOpen = file("credit", "Của Linh mở");
        long mineResolved = file("credit", "Của Linh xong");
        long others = file("credit", "Của Minh");
        place(mineOpen, linh.id(), "in_progress");
        place(mineResolved, linh.id(), "resolved");
        place(others, minh.id(), "in_progress");

        PageView page = list(linhToken, "?assignee=me&status=in_progress");

        assertThat(page.items()).extracting(SummaryView::number).containsExactly(mineOpen);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().assignee().email()).isEqualTo(linh.email());
    }

    @Test
    void unassignedFromTheLastWeekLeavesOutOlderRequests() {
        long recent = file("credit", "Gần đây");
        long old = file("credit", "Từ lâu");
        jdbcTemplate.update("UPDATE support_request SET created_at = now() - interval '3 days' WHERE number = ?",
                recent);
        jdbcTemplate.update("UPDATE support_request SET created_at = now() - interval '10 days' WHERE number = ?",
                old);

        PageView page = list(minhToken, "?assignee=unassigned&created=7d");

        assertThat(page.items()).extracting(SummaryView::number).containsExactly(recent);
        assertThat(list(minhToken, "?created=30d").items()).extracting(SummaryView::number).containsExactly(old);
    }

    @Test
    void searchFindsANumberWithAHashAndASubjectIgnoringCase() {
        long video = file("technical", "Không tải được video");
        long payment = file("credit", "Payment failed");

        assertThat(list(minhToken, "?q={q}", "#" + video).items()).extracting(SummaryView::number)
                .containsExactly(video);
        assertThat(list(minhToken, "?q={q}", "TẢI ĐƯỢC").items()).extracting(SummaryView::number)
                .containsExactly(video);
        assertThat(list(minhToken, "?q={q}", "100%_").items()).isEmpty();
        assertThat(list(minhToken, "?q={q}", "payment").items()).extracting(SummaryView::number)
                .containsExactly(payment);
    }

    @Test
    void oldestFirstOrdersByLastActivityAscending() {
        long earlier = file("credit", "Hôm qua");
        long later = file("credit", "Một giờ trước");
        jdbcTemplate.update("UPDATE support_request SET last_activity_at = now() - interval '1 day' "
                + "WHERE number = ?", earlier);
        jdbcTemplate.update("UPDATE support_request SET last_activity_at = now() - interval '1 hour' "
                + "WHERE number = ?", later);

        assertThat(list(minhToken, "?sort=oldest").items()).extracting(SummaryView::number)
                .containsExactly(earlier, later);
        assertThat(list(minhToken, "").items()).extracting(SummaryView::number).containsExactly(later, earlier);
    }

    @Test
    void anUnknownFilterIsA400NamingTheParameter() {
        for (String parameter : List.of("created=yesterday", "assignee=bob", "sort=top")) {
            ResponseEntity<Problem> list = restTemplate.exchange(ADMIN_REQUESTS + "?" + parameter, HttpMethod.GET,
                    new HttpEntity<>(bearer(minhToken)), Problem.class);

            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(list.getBody().errors()).containsOnlyKeys(parameter.substring(0, parameter.indexOf('=')));
        }
        for (String parameter : List.of("created=yesterday", "assignee=bob")) {
            ResponseEntity<Problem> facets = restTemplate.exchange(ADMIN_REQUESTS + "/facets?" + parameter,
                    HttpMethod.GET, new HttpEntity<>(bearer(minhToken)), Problem.class);

            assertThat(facets.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<Problem> blank = restTemplate.exchange(ADMIN_REQUESTS + "?q={q}", HttpMethod.GET,
                new HttpEntity<>(bearer(minhToken)), Problem.class, "   ");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody().errors()).containsOnlyKeys("q");
    }

    @Test
    void countsIgnoreTheirOwnSelection() {
        file("credit", "Yêu cầu A");
        file("credit", "Yêu cầu B");
        file("other", "Yêu cầu C");
        long d = file("credit", "Yêu cầu D");
        long e = file("technical", "Yêu cầu E");
        place(d, null, "in_progress");
        place(e, linh.id(), "in_progress");

        Facets facets = restTemplate.exchange(ADMIN_REQUESTS + "/facets?status=new&type=credit", HttpMethod.GET,
                new HttpEntity<>(bearer(linhToken)), Facets.class).getBody();

        assertThat(facets.status()).extracting(Count::value, Count::count).contains(
                org.assertj.core.groups.Tuple.tuple("new", 2L), org.assertj.core.groups.Tuple.tuple("in_progress", 1L),
                org.assertj.core.groups.Tuple.tuple("closed", 0L));
        assertThat(facets.type()).extracting(Count::value, Count::count).contains(
                org.assertj.core.groups.Tuple.tuple("credit", 2L), org.assertj.core.groups.Tuple.tuple("other", 1L),
                org.assertj.core.groups.Tuple.tuple("technical", 0L));
        assertThat(facets.assignee().unassigned()).isEqualTo(2);
        assertThat(facets.assignee().mine()).isZero();
        assertThat(facets.created()).extracting(Count::value, Count::count)
                .contains(org.assertj.core.groups.Tuple.tuple("today", 2L));
    }

    @Test
    void theAssigneeCountsNameTheLargestGroupsAndTheCallersOwn() {
        long first = file("credit", "Yêu cầu một");
        long second = file("credit", "Yêu cầu hai");
        long third = file("credit", "Yêu cầu ba");
        place(first, linh.id(), "in_progress");
        place(second, linh.id(), "in_progress");
        place(third, minh.id(), "in_progress");

        Facets facets = restTemplate.exchange(ADMIN_REQUESTS + "/facets", HttpMethod.GET,
                new HttpEntity<>(bearer(linhToken)), Facets.class).getBody();

        assertThat(facets.assignee().mine()).isEqualTo(2);
        assertThat(facets.assignee().people()).extracting(AssigneeCount::email, AssigneeCount::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(linh.email(), 2L),
                        org.assertj.core.groups.Tuple.tuple(minh.email(), 1L));
    }

    private ResponseEntity<Map<String, Object>> pickUp(String token, long number, UUID self) {
        return restTemplate.exchange(ADMIN_REQUESTS + "/" + number + "/assignee", HttpMethod.PUT,
                new HttpEntity<>(new AssigneeBody(self), bearer(token)),
                new org.springframework.core.ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<DetailView> assign(String token, long number, UUID assignee) {
        return restTemplate.exchange(ADMIN_REQUESTS + "/" + number + "/assignee", HttpMethod.PUT,
                new HttpEntity<>(new AssigneeBody(assignee), bearer(token)), DetailView.class);
    }

    private PageView list(String token, String query, Object... variables) {
        ResponseEntity<PageView> response = restTemplate.exchange(ADMIN_REQUESTS + query, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), PageView.class, variables);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private long file(String type, String subject) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange("/api/v1/support/requests",
                HttpMethod.POST, new HttpEntity<>(new CreateRequest(type, subject,
                        "Mô tả chi tiết vấn đề gặp phải.", List.of()), bearer(memberToken)),
                new org.springframework.core.ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CREATED);
        return ((Number) response.getBody().get("number")).longValue();
    }

    /** Puts a request into a state the API reaches only through several steps. */
    private void place(long number, UUID assignee, String status) {
        jdbcTemplate.update("UPDATE support_request SET status = ?, assignee_id = ?, "
                + "assigned_at = CASE WHEN ?::uuid IS NULL THEN NULL ELSE now() END WHERE number = ?", status,
                assignee, assignee, number);
    }

    private Timestamp lastActivity(long number) {
        return jdbcTemplate.queryForObject("SELECT last_activity_at FROM support_request WHERE number = ?",
                Timestamp.class, number);
    }

    /** Waits until {@code count} sessions are blocked on the request row the test holds locked. */
    private void awaitBlockedUpdates(int count) throws RuntimeException {
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(10);
        while (java.time.Instant.now().isBefore(deadline)) {
            // Inside a transaction pg_stat_activity is a snapshot taken at first read.
            jdbcTemplate.queryForList("SELECT pg_stat_clear_snapshot()");
            Long blocked = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND query LIKE 'UPDATE support_request%'", Long.class);
            if (blocked != null && blocked >= count) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Never " + count + " sessions blocked on the request row");
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, PASSWORD),
                TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
