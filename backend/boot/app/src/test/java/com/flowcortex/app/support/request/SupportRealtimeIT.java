package com.flowcortex.app.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.app.realtime.SseTestClient;
import com.flowcortex.app.realtime.SseTestClient.Open;
import com.flowcortex.realtime.api.RealtimeEvents;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Support requests against the realtime hub on the fully wired application: what the unread-clearing write
 * condition signals, and who the hub lets follow a request topic.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.support.mail.interval=PT24H",
        "flowcortex.support.retention.interval=PT24H",
        "flowcortex.realtime.recheck-interval=PT1S"})
@AutoConfigureTestRestTemplate
class SupportRealtimeIT extends AbstractPostgresBootTest {

    private static final String PASSWORD = "password123";
    private static final String CHANGED = "event: support.request.changed";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RealtimeEvents events;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Open> open = new CopyOnWriteArrayList<>();

    private UserView admin;
    private String adminToken;
    private UserView member;
    private String memberToken;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record CreateRequest(String type, String subject, String description, List<UUID> uploadIds) {
    }

    record AdminReplyRequest(String body, List<UUID> uploadIds, String status) {
    }

    record DetailView(long number, String status) {
    }

    record Count(long count) {
    }

    @BeforeEach
    void fixture() {
        jdbcTemplate.update("DELETE FROM support_request");
        String run = UUID.randomUUID().toString().substring(0, 8);
        admin = userService.createInitialAdmin("rt-admin-" + run + "@nodi.test", PASSWORD, "Admin");
        member = userService.createUser(admin.id(), "rt-member-" + run + "@nodi.test", PASSWORD, "Member", "MEMBER");
        adminToken = login(admin.email());
        memberToken = login(member.email());
    }

    @AfterEach
    void closeStreams() {
        open.forEach(Open::close);
    }

    @Test
    void firstOpenOfAnUnreadReplySignalsOnceAndReopeningSignalsNothing() throws Exception {
        long number = fileRequest();
        replyAsAdmin(number);
        Open stream = open("?topic=user", memberToken);
        assertThat(stream.status()).isEqualTo(200);
        marker(stream, "it.ready");
        long before = stream.count(CHANGED::equals);

        assertThat(unreadCount()).isEqualTo(1);
        openRequest(number);
        assertThat(unreadCount()).isZero();
        awaitCount(stream, before + 1);

        openRequest(number);
        marker(stream, "it.after-second-open");

        assertThat(stream.count(CHANGED::equals)).isEqualTo(before + 1);
    }

    @Test
    void theSupportAuthorizerIsRegisteredWithTheHub() throws Exception {
        long number = fileRequest();

        Open staff = open("?topic=support:request:" + number, adminToken);
        Open requester = open("?topic=support:request:" + number, memberToken);
        Open missing = open("?topic=support:request:" + (number + 1000), adminToken);

        assertThat(staff.status()).isEqualTo(200);
        assertThat(requester.status()).isEqualTo(404);
        assertThat(missing.status()).isEqualTo(404);
    }

    @Test
    void aDeactivatedStaffStreamEndsAtTheNextRecheckAndCannotReopen() throws Exception {
        long number = fileRequest();
        UserView staff = userService.createUser(admin.id(), "rt-staff-" + UUID.randomUUID().toString().substring(0, 8)
                + "@nodi.test", PASSWORD, "Staff", "ADMIN");
        String staffToken = login(staff.email());
        Open stream = open("?topic=support:request:" + number, staffToken);
        assertThat(stream.status()).isEqualTo(200);

        jdbcTemplate.update("UPDATE app_user SET admin_disabled_at = now(), admin_disabled_by = ? WHERE id = ?",
                admin.id(), staff.id());

        stream.awaitEnd();
        assertThat(stream.seen()).noneMatch(line -> line.startsWith("event: error"));
        assertThat(open("?topic=support:request:" + number, staffToken).status()).isEqualTo(404);
    }

    private long fileRequest() {
        ResponseEntity<DetailView> response = exchange(HttpMethod.POST, "/api/v1/support/requests", memberToken,
                new CreateRequest("other", "Cần hỗ trợ", "Mô tả chi tiết vấn đề gặp phải.", List.of()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().number();
    }

    private void replyAsAdmin(long number) {
        ResponseEntity<DetailView> response = exchange(HttpMethod.POST,
                "/api/v1/admin/support/requests/" + number + "/messages", adminToken,
                new AdminReplyRequest("Đã xem, bạn gửi thêm thông tin nhé", List.of(), "waiting_member"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void openRequest(long number) {
        ResponseEntity<DetailView> response = exchange(HttpMethod.GET, "/api/v1/support/requests/" + number,
                memberToken, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private long unreadCount() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(memberToken);
        ResponseEntity<Count> response = restTemplate.exchange("/api/v1/support/requests/unread-count",
                HttpMethod.GET, new HttpEntity<>(headers), Count.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().count();
    }

    /** Events on one topic reach a stream in publish order, so once the marker is here every earlier signal is. */
    private void marker(Open stream, String type) throws InterruptedException {
        events.publish("user:" + member.id(), type, Map.of());
        stream.await(line -> line.equals("event: " + type));
    }

    private void awaitCount(Open stream, long count) throws InterruptedException {
        stream.await(line -> line.equals(CHANGED) && stream.count(CHANGED::equals) >= count);
    }

    private Open open(String query, String token) throws Exception {
        Open stream = SseTestClient.open(http, port, query, token, null);
        open.add(stream);
        return stream;
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, PASSWORD),
                TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private ResponseEntity<DetailView> exchange(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), DetailView.class);
    }
}
