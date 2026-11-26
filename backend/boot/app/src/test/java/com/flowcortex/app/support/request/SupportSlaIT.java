package com.flowcortex.app.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
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
 * The SLA summary and the at-risk filter of the admin inbox over HTTP: the counts under filters, the list that
 * agrees with them and labels each row, the refusals, and a threshold changed at run time.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.support.mail.interval=PT24H",
        "flowcortex.support.retention.interval=PT24H",
        "flowcortex.support.sla.sweep-interval=PT24H"})
@AutoConfigureTestRestTemplate
class SupportSlaIT extends AbstractPostgresBootTest {

    private static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");
    private static final String PASSWORD = "password123";
    private static final String ADMIN_REQUESTS = "/api/v1/admin/support/requests";
    private static final String NEW_HOURS = "support.sla.new_hours";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserView linh;
    private String superAdminToken;
    private String minhToken;
    private String linhToken;
    private String memberToken;
    private long overdueNew;
    private long dueSoonNew;
    private long overdueInProgress;
    private long waiting;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record CreateRequest(String type, String subject, String description, List<UUID> uploadIds) {
    }

    record SummaryView(long number, String status, String slaLevel) {
    }

    record PageView(List<SummaryView> items, long total) {
    }

    record SlaSummary(long overdue, long dueSoon) {
    }

    record Problem(int status, URI type, String detail, Map<String, String> errors) {
    }

    @BeforeEach
    void fixture() {
        jdbcTemplate.update("DELETE FROM support_request");
        String run = UUID.randomUUID().toString().substring(0, 8);
        UserView superAdmin = userService.createInitialAdmin("sla-admin-" + run + "@nodi.test", PASSWORD, "Admin");
        UserView minh = userService.createUser(superAdmin.id(), "sla-minh-" + run + "@nodi.test", PASSWORD, "Minh",
                "OPERATION_MANAGER");
        linh = userService.createUser(superAdmin.id(), "sla-linh-" + run + "@nodi.test", PASSWORD, "Linh",
                "CUSTOMER_SUPPORT");
        UserView member = userService.createUser(superAdmin.id(), "sla-member-" + run + "@nodi.test", PASSWORD,
                "Member", "MEMBER");
        superAdminToken = login(superAdmin.email());
        minhToken = login(minh.email());
        linhToken = login(linh.email());
        memberToken = login(member.email());

        overdueNew = seed("Chưa ai trả lời", "new", "25 hours", null);
        dueSoonNew = seed("Sắp quá hạn", "new", "21 hours", null);
        overdueInProgress = seed("Đang xử lý lâu", "in_progress", "4 days", linh.id());
        waiting = seed("Chờ thành viên", "waiting_member", "10 days", null);
    }

    @AfterEach
    void restoreThresholds() {
        restTemplate.exchange("/api/v1/admin/settings/" + NEW_HOURS, HttpMethod.DELETE,
                new HttpEntity<>(bearer(superAdminToken)), String.class);
    }

    @Test
    void summaryCountsUnderFilters() {
        assertThat(summary(linhToken, "?assignee=me")).isEqualTo(new SlaSummary(1, 0));
        assertThat(summary(linhToken, "")).isEqualTo(new SlaSummary(2, 1));
        assertThat(summary(minhToken, "?status=new")).isEqualTo(new SlaSummary(1, 1));
        assertThat(summary(minhToken, "?status=waiting_member")).isEqualTo(new SlaSummary(0, 0));
        assertThat(summary(minhToken, "?assignee=unassigned&type=credit&created=today"))
                .isEqualTo(new SlaSummary(1, 1));
        assertThat(summary(minhToken, "?q=" + dueSoonNew)).isEqualTo(new SlaSummary(0, 1));
        assertThat(summary(minhToken, "?q=quá hạn")).isEqualTo(new SlaSummary(0, 1));
    }

    @Test
    void atRiskListMatchesTheSummaryAndLabels() {
        PageView atRisk = list(minhToken, "?sla=at_risk&sort=oldest");

        assertThat(atRisk.items()).extracting(SummaryView::number, SummaryView::slaLevel)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(overdueInProgress, "overdue"),
                        org.assertj.core.groups.Tuple.tuple(overdueNew, "overdue"),
                        org.assertj.core.groups.Tuple.tuple(dueSoonNew, "due_soon"));
        assertThat(atRisk.total()).isEqualTo(3);

        PageView everything = list(minhToken, "?sort=oldest");
        assertThat(everything.items()).extracting(SummaryView::number, SummaryView::slaLevel)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(waiting, "paused"),
                        org.assertj.core.groups.Tuple.tuple(overdueInProgress, "overdue"),
                        org.assertj.core.groups.Tuple.tuple(overdueNew, "overdue"),
                        org.assertj.core.groups.Tuple.tuple(dueSoonNew, "due_soon"));

        for (String filters : List.of("", "&assignee=me", "&status=new", "&type=credit&created=today", "&q=quá")) {
            SlaSummary counted = summary(linhToken, filters.isEmpty() ? "" : "?" + filters.substring(1));
            PageView rows = list(linhToken, "?sla=at_risk" + filters);
            assertThat(rows.total()).as(filters).isEqualTo(counted.overdue() + counted.dueSoon());
            assertThat(rows.items().stream().filter(row -> row.slaLevel().equals("overdue")).count()).as(filters)
                    .isEqualTo(counted.overdue());
            assertThat(rows.items().stream().filter(row -> row.slaLevel().equals("due_soon")).count()).as(filters)
                    .isEqualTo(counted.dueSoon());
        }
    }

    @Test
    void unknownSlaIsAFieldError() {
        ResponseEntity<Problem> response = restTemplate.exchange(ADMIN_REQUESTS + "?sla=late", HttpMethod.GET,
                new HttpEntity<>(bearer(minhToken)), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
        assertThat(response.getBody().errors()).containsOnlyKeys("sla");
    }

    @Test
    void summaryWithoutViewIsForbidden() {
        ResponseEntity<Problem> response = restTemplate.exchange(ADMIN_REQUESTS + "/sla-summary", HttpMethod.GET,
                new HttpEntity<>(bearer(memberToken)), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("forbidden"));
    }

    @Test
    void invalidCreatedIsAFieldError() {
        ResponseEntity<Problem> response = restTemplate.exchange(ADMIN_REQUESTS + "/sla-summary?created=yesterday",
                HttpMethod.GET, new HttpEntity<>(bearer(minhToken)), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
        assertThat(response.getBody().errors()).containsOnlyKeys("created");
    }

    @Test
    void aLoweredThresholdAppliesWithoutRestart() {
        long fresh = seed("Mười giờ", "new", "10 hours", null);
        assertThat(level(fresh)).isEqualTo("ok");

        HttpHeaders headers = bearer(superAdminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> put = restTemplate.exchange("/api/v1/admin/settings/" + NEW_HOURS, HttpMethod.PUT,
                new HttpEntity<>("{\"value\":8}", headers), String.class);
        assertThat(put.getStatusCode()).as(put.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(level(fresh)).isEqualTo("overdue");
        assertThat(summary(minhToken, "?q=Mười giờ")).isEqualTo(new SlaSummary(1, 0));
    }

    private String level(long number) {
        return list(minhToken, "?q=" + number).items().stream().filter(row -> row.number() == number).findFirst()
                .orElseThrow().slaLevel();
    }

    private SlaSummary summary(String token, String query) {
        ResponseEntity<SlaSummary> response = restTemplate.exchange(ADMIN_REQUESTS + "/sla-summary" + query,
                HttpMethod.GET, new HttpEntity<>(bearer(token)), SlaSummary.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PageView list(String token, String query) {
        ResponseEntity<PageView> response = restTemplate.exchange(ADMIN_REQUESTS + query, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), PageView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** Files a request over the API, then puts it in a state, with an activity age, the API reaches in many steps. */
    private long seed(String subject, String status, String age, UUID assignee) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange("/api/v1/support/requests",
                HttpMethod.POST, new HttpEntity<>(new CreateRequest("credit", subject,
                        "Mô tả chi tiết vấn đề gặp phải.", List.of()), bearer(memberToken)),
                new ParameterizedTypeReference<>() {
                });
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CREATED);
        long number = ((Number) response.getBody().get("number")).longValue();
        jdbcTemplate.update("UPDATE support_request SET status = ?, assignee_id = ?, "
                + "assigned_at = CASE WHEN ?::uuid IS NULL THEN NULL ELSE now() END, "
                + "last_activity_at = now() - CAST(? AS interval) WHERE number = ?", status, assignee, assignee, age,
                number);
        return number;
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
