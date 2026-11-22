package com.flowcortex.app.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.notification.api.NotificationRequested;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.support.config.SupportSlaSettings;
import com.flowcortex.support.request.SlaSweepJob;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SLA sweep run directly against a real database and the real notification inbox: who is alerted and how
 * often, the cycle and reassignment rules, a stale backlog that must not hide a fresh crossing, a request that
 * changes under the sweep, one failing alert, the switch, and the stored alert read in the reader's language.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.support.mail.interval=PT24H",
        "flowcortex.support.retention.interval=PT24H",
        "flowcortex.support.sla.sweep-interval=PT24H"})
@AutoConfigureTestRestTemplate
class SupportSlaSweepIT extends AbstractPostgresBootTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String PASSWORD = "password123";
    private static final String POISONED = "Lỗi khi gửi";

    @Autowired
    private SlaSweepJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Settings settings;

    @Autowired
    private UserService userService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UserView admin;
    private UserView linh;
    private UserView minh;
    private UserView member;
    private List<UUID> parked = List.of();

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record ItemView(UUID id, String type, String title, String body, String link, boolean read) {
    }

    record PageView(List<ItemView> items, long unreadCount) {
    }

    /** Fails the publish of one alert, in the alert's own transaction. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingPublisher {

        static final AtomicReference<String> FAIL_FOR_SUBJECT = new AtomicReference<>();

        @Bean
        PoisonListener poisonListener() {
            return new PoisonListener();
        }

        static class PoisonListener {

            @EventListener
            void on(NotificationRequested event) {
                String poisoned = FAIL_FOR_SUBJECT.get();
                if (poisoned != null && event.args().contains(poisoned)) {
                    throw new IllegalStateException("alert refused");
                }
            }
        }
    }

    @BeforeEach
    void fixture() {
        FailingPublisher.FAIL_FOR_SUBJECT.set(null);
        jdbc.update("DELETE FROM support_request");
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'support.sla.%'");
        jdbc.update("DELETE FROM app_setting_change WHERE key LIKE 'support.sla.%'");
        String run = UUID.randomUUID().toString().substring(0, 8);
        admin = userService.createInitialAdmin("sweep-admin-" + run + "@nodi.test", PASSWORD, "Admin");
        minh = userService.createUser(admin.id(), "sweep-minh-" + run + "@nodi.test", PASSWORD, "Minh",
                "OPERATION_MANAGER");
        linh = userService.createUser(admin.id(), "sweep-linh-" + run + "@nodi.test", PASSWORD, "Linh",
                "CUSTOMER_SUPPORT");
        member = userService.createUser(admin.id(), "sweep-member-" + run + "@nodi.test", PASSWORD, "Member",
                "MEMBER");
        // The sweep alerts at most 500 holders, first by email, and the suite's other classes leave hundreds
        // of console accounts in the shared database; set them aside so the holders are this test's own.
        parked = jdbc.queryForList("UPDATE app_user SET admin_disabled_at = now(), admin_disabled_by = ? "
                + "WHERE admin_disabled_at IS NULL AND role <> 'MEMBER' AND id NOT IN (?, ?, ?) RETURNING id",
                UUID.class, admin.id(), admin.id(), minh.id(), linh.id());
    }

    @AfterEach
    void restoreSettings() {
        jdbc.batchUpdate("UPDATE app_user SET admin_disabled_at = NULL, admin_disabled_by = NULL WHERE id = ?",
                parked.stream().map(id -> new Object[] {id}).toList());
        FailingPublisher.FAIL_FOR_SUBJECT.set(null);
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'support.sla.%'");
        jdbc.update("DELETE FROM app_setting_change WHERE key LIKE 'support.sla.%'");
    }

    @Test
    void aDueSoonCrossingAlertsEveryHolderOnce() {
        long number = request("Sắp quá hạn", "new", Duration.ofHours(20).plusMinutes(3), null);

        job.run();
        job.run();
        settle();

        for (UUID holder : List.of(admin.id(), linh.id(), minh.id())) {
            assertThat(alerts(holder, number)).as(holder.toString())
                    .containsExactly("notification.admin.supportSla.dueSoon.title");
        }
        assertThat(alerts(member.id(), number)).isEmpty();
        assertThat(claims(number)).containsExactly("due_soon");
    }

    @Test
    void anOverdueAlertBlocksALaterDueSoonInTheCycle() {
        long number = request("Quá hạn", "new", Duration.ofHours(25), null);

        job.run();
        settle();

        for (UUID holder : List.of(admin.id(), linh.id(), minh.id())) {
            assertThat(alerts(holder, number)).containsExactly("notification.admin.supportSla.overdue.title");
        }
        assertThat(claims(number)).containsExactlyInAnyOrder("due_soon", "overdue");

        settings.set(SupportSlaSettings.NEW_HOURS, 48, admin.id());
        job.run();
        settle();

        for (UUID holder : List.of(admin.id(), linh.id(), minh.id())) {
            assertThat(alerts(holder, number)).containsExactly("notification.admin.supportSla.overdue.title");
        }
    }

    @Test
    void anAssignedRequestAlertsOnlyTheAssignee() {
        long number = request("Đang xử lý", "in_progress", Duration.ofDays(2).plusHours(1), linh.id());

        job.run();
        settle();

        assertThat(alerts(linh.id(), number)).containsExactly("notification.admin.supportSla.dueSoon.title");
        assertThat(alerts(minh.id(), number)).isEmpty();
        assertThat(alerts(admin.id(), number)).isEmpty();
    }

    @Test
    void anAssigneeWithoutTheViewPermissionLeavesItToTheHolders() {
        long number = request("Người nhận mất quyền", "in_progress", Duration.ofDays(2).plusHours(1), member.id());

        job.run();
        settle();

        assertThat(alerts(member.id(), number)).isEmpty();
        assertThat(alerts(linh.id(), number)).containsExactly("notification.admin.supportSla.dueSoon.title");
        assertThat(alerts(minh.id(), number)).containsExactly("notification.admin.supportSla.dueSoon.title");
    }

    @Test
    void aStaleBacklogIsNotAlerted() {
        long number = request("Tồn đọng từ lâu", "new", Duration.ofDays(3), null);

        job.run();
        settle();

        assertThat(alerts(linh.id(), number)).isEmpty();
        assertThat(claims(number)).isEmpty();
    }

    @Test
    void aStaleBacklogOf600DoesNotHideAFreshCrossing() {
        jdbc.update("INSERT INTO support_request (id, requester_id, type, subject, status, member_unread, "
                + "last_activity_at, created_at, updated_at) SELECT gen_random_uuid(), gen_random_uuid(), 'credit', "
                + "'Tồn đọng ' || n, 'new', false, ?, ?, ? FROM generate_series(1, 600) n",
                ts(Duration.ofDays(3)), ts(Duration.ofDays(3)), ts(Duration.ofDays(3)));
        long fresh = request("Vừa chạm ngưỡng", "new", Duration.ofHours(20).plusMinutes(10), null);

        job.run();
        settle();

        assertThat(alerts(linh.id(), fresh)).containsExactly("notification.admin.supportSla.dueSoon.title");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_sla_alert", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ? "
                + "AND type = 'admin_support_sla'", Long.class, linh.id())).isEqualTo(1);
    }

    @Test
    void aReassignmentInTheCycleAlertsTheNewAssigneeOnce() {
        long number = request("Chuyển người xử lý", "in_progress", Duration.ofDays(2).plusHours(1), linh.id());
        job.run();
        settle();
        assertThat(alerts(linh.id(), number)).hasSize(1);

        jdbc.update("UPDATE support_request SET assignee_id = ? WHERE number = ?", minh.id(), number);
        job.run();
        job.run();
        settle();

        assertThat(alerts(minh.id(), number)).containsExactly("notification.admin.supportSla.dueSoon.title");
        assertThat(alerts(linh.id(), number)).hasSize(1);
    }

    @Test
    void aNewActivityStartsANewCycle() {
        long number = request("Thành viên trả lời lại", "new", Duration.ofHours(23), null);
        job.run();
        settle();
        assertThat(alerts(linh.id(), number)).hasSize(1);

        jdbc.update("UPDATE support_request SET last_activity_at = ? WHERE number = ?",
                ts(Duration.ofHours(20).plusMinutes(30)), number);
        job.run();
        settle();

        assertThat(alerts(linh.id(), number)).hasSize(2);
        assertThat(alerts(minh.id(), number)).hasSize(2);
    }

    @Test
    void aChangedRequestPublishesNothingAndKeepsNoClaim() throws Exception {
        long number = request("Được nhận trong lúc quét", "new", Duration.ofHours(20).plusMinutes(30), null);
        UUID id = jdbc.queryForObject("SELECT id FROM support_request WHERE number = ?", UUID.class, number);
        AtomicReference<CompletableFuture<Void>> run = new AtomicReference<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM support_request WHERE id = ? FOR UPDATE", UUID.class, id);
            run.set(CompletableFuture.runAsync(job::run));
            awaitBlockedClaim();
            // An admin takes the request before the sweep's transaction re-reads it.
            jdbc.update("UPDATE support_request SET status = 'in_progress', assignee_id = ?, assigned_at = now(), "
                    + "last_activity_at = now() WHERE id = ?", linh.id(), id);
        });
        run.get().get(WAIT.toSeconds(), TimeUnit.SECONDS);
        settle();

        assertThat(alerts(linh.id(), number)).isEmpty();
        assertThat(alerts(minh.id(), number)).isEmpty();
        assertThat(claims(number)).isEmpty();

        job.run();
        settle();
        assertThat(alerts(linh.id(), number)).isEmpty();
    }

    @Test
    void oneFailingAlertDoesNotStopTheRunAndIsAlertedLater() {
        long failing = request(POISONED, "new", Duration.ofHours(20).plusMinutes(40), null);
        long healthy = request("Yêu cầu bình thường", "new", Duration.ofHours(20).plusMinutes(20), null);
        FailingPublisher.FAIL_FOR_SUBJECT.set(POISONED);

        job.run();
        settle();

        assertThat(alerts(linh.id(), healthy)).hasSize(1);
        assertThat(alerts(linh.id(), failing)).isEmpty();
        assertThat(claims(failing)).isEmpty();

        FailingPublisher.FAIL_FOR_SUBJECT.set(null);
        job.run();
        settle();

        assertThat(alerts(linh.id(), failing)).hasSize(1);
        assertThat(alerts(linh.id(), healthy)).hasSize(1);
    }

    @Test
    void alertsSwitchedOffPublishNothingButPrune() {
        settings.set(SupportSlaSettings.ALERTS_ENABLED, false, admin.id());
        long overdue = request("Quá hạn khi tắt cảnh báo", "new", Duration.ofHours(25), null);
        long old = request("Có claim cũ", "new", Duration.ofHours(1), null);
        insertClaim(old, "due_soon", Duration.ofDays(31));
        insertClaim(old, "overdue", Duration.ofDays(29));

        job.run();
        settle();

        assertThat(alerts(linh.id(), overdue)).isEmpty();
        assertThat(claims(overdue)).isEmpty();
        assertThat(claims(old)).containsExactly("overdue");
    }

    @Test
    void theStoredAlertRendersInTheReadersLanguage() {
        long number = request("Không tải được video", "new", Duration.ofHours(25), null);
        job.run();
        settle();
        String token = login(minh.email());

        ItemView english = adminItem(token, "en", number);
        assertThat(english.title()).isEqualTo("Request #" + number + " is overdue");
        assertThat(english.body()).isEqualTo("Không tải được video");
        assertThat(english.link()).isEqualTo("/support/requests/" + number);
        assertThat(english.type()).isEqualTo("admin_support_sla");

        assertThat(adminItem(token, "vi", number).title()).isEqualTo("#" + number + " đã quá hạn xử lý");
    }

    private ItemView adminItem(String token, String language, long number) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        ResponseEntity<PageView> response = restTemplate.exchange("/api/v1/notifications?audience=admin&size=50",
                HttpMethod.GET, new HttpEntity<>(headers), PageView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().items().stream().filter(item -> ("/support/requests/" + number).equals(item.link()))
                .findFirst().orElseThrow();
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, PASSWORD),
                TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private long request(String subject, String status, Duration age, UUID assignee) {
        Timestamp activity = ts(age);
        return jdbc.queryForObject("INSERT INTO support_request (id, requester_id, type, subject, status, "
                + "assignee_id, assigned_at, member_unread, last_activity_at, created_at, updated_at) "
                + "VALUES (?, ?, 'credit', ?, ?, ?, ?, false, ?, ?, ?) RETURNING number", Long.class,
                UUID.randomUUID(), member.id(), subject, status, assignee,
                assignee == null ? null : Timestamp.from(Instant.now()), activity, activity, activity);
    }

    private void insertClaim(long number, String level, Duration createdAgo) {
        jdbc.update("INSERT INTO support_sla_alert (request_id, level, cycle_at, assignee_id, created_at) "
                + "SELECT id, ?, last_activity_at, assignee_id, ? FROM support_request WHERE number = ?", level,
                ts(createdAgo), number);
    }

    private static Timestamp ts(Duration age) {
        return Timestamp.from(Instant.now().minus(age));
    }

    /** The title codes of the admin alerts a user holds for one request, oldest first. */
    private List<String> alerts(UUID userId, long number) {
        return jdbc.queryForList("SELECT title_code FROM notification WHERE user_id = ? "
                + "AND type = 'admin_support_sla' AND link = ? ORDER BY created_at, id", String.class, userId,
                "/support/requests/" + number);
    }

    private List<String> claims(long number) {
        return jdbc.queryForList("SELECT level FROM support_sla_alert a JOIN support_request r ON r.id = a.request_id "
                + "WHERE r.number = ? ORDER BY level", String.class, number);
    }

    /** Waits until every published notification has been stored, so a count that must stay zero is final. */
    private void settle() {
        await().atMost(WAIT).until(() -> jdbc.queryForObject("SELECT count(*) FROM event_publication "
                + "WHERE completion_date IS NULL AND event_type LIKE '%NotificationRequested%'", Long.class) == 0);
    }

    /** Waits until the sweep's claim is blocked on the request row the test holds locked. */
    private void awaitBlockedClaim() {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            // Inside a transaction pg_stat_activity is a snapshot taken at first read.
            jdbc.queryForList("SELECT pg_stat_clear_snapshot()");
            Long blocked = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND query LIKE 'INSERT INTO support_sla_alert%'", Long.class);
            if (blocked != null && blocked >= 1) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("The sweep's claim never reached the row lock");
    }
}
