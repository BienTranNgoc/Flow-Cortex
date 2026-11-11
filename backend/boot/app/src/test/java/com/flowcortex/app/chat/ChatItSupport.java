package com.flowcortex.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.chat.turn.TurnRunner;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Members, tokens, rows and requests the chat ITs share; each case starts with a clean provider script. */
abstract class ChatItSupport extends AbstractPostgresBootTest {

    static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");
    static final String BASE = "/api/v1/chat/conversations";
    static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected UserService userService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ChatTestConfig.StubChatClient stub;

    @Autowired
    protected ChatTestConfig.RecordingEvents events;

    @Autowired
    protected TurnRunner runner;

    @Autowired
    protected ChatTestConfig.SwitchableChatSwitch chatSwitch;

    protected UserView admin;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record Att(UUID uploadId, String fileName) {
    }

    record SendRequest(String text, List<Att> attachments) {
    }

    record Msg(UUID id, int seq, String role, String kind, String body, String status) {
    }

    record Sent(Msg userMessage, UUID assistantMessageId) {
    }

    record Problem(int status, URI type, String detail, String code, Map<String, String> errors, Instant resumeAt) {
    }

    @BeforeEach
    void chatSetUp() {
        admin = userService.createInitialAdmin(uniqueEmail("chat-admin"), "password123", "Chat Admin");
        jdbcTemplate.update("UPDATE chat_settings SET enabled = true, updated_by = NULL WHERE id = 1");
        jdbcTemplate.update("DELETE FROM chat_quota_usage WHERE subject = 'global'");
        chatSwitch.environmentEnabled(true);
        stub.reset();
        events.reset();
        setSeededPrice("echo", 10);
        setSeededPrice("probe", 40);
    }

    @AfterEach
    void restoreEnvironmentSwitch() {
        chatSwitch.environmentEnabled(true);
    }

    @AfterEach
    void restoreSeededPrices() {
        setSeededPrice("echo", 1);
        setSeededPrice("probe", 10);
    }

    private void setSeededPrice(String ref, int credits) {
        int updated = jdbcTemplate.update("UPDATE rate_card SET credits_per_unit = ? WHERE rate_card_ref = ? "
                + "AND effective_to IS NULL", credits, ref);
        assertThat(updated).as("seeded open rate_card row of %s", ref).isEqualTo(1);
    }

    /** Runs started by a confirmed card carry remote steps the admin media-jobs counts would otherwise see. */
    @AfterEach
    void removeCardRuns() {
        String runs = "SELECT id FROM workflow_run WHERE request_key LIKE 'chat-card:%'";
        jdbcTemplate.update("DELETE FROM media_asset_binding WHERE run_id IN (" + runs + ")");
        jdbcTemplate.update("DELETE FROM run_step WHERE run_id IN (" + runs + ")");
        jdbcTemplate.update("DELETE FROM workflow_run WHERE request_key LIKE 'chat-card:%'");
    }

    protected <T> ResponseEntity<T> send(String token, UUID conversationId, String key, String text,
            Class<T> type) {
        return restTemplate.exchange(BASE + "/" + conversationId + "/messages", HttpMethod.POST,
                new HttpEntity<>(new SendRequest(text, null), idempotent(token, key)), type);
    }

    protected Sent sendOk(String token, UUID conversationId, String text) {
        ResponseEntity<Sent> response = send(token, conversationId, UUID.randomUUID().toString(), text, Sent.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        return response.getBody();
    }

    protected HttpHeaders idempotent(String token, String key) {
        HttpHeaders headers = bearer(token);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        return headers;
    }

    protected UUID conversation(UUID ownerId, String title) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO chat_conversation (id, owner_id, title, created_at, updated_at, "
                + "last_activity_at) VALUES (?, ?, ?, ?, ?, ?)", id, ownerId, title, at, at, at);
        return id;
    }

    protected UUID seedMessage(UUID conversationId, int seq, String role, String body, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO chat_message (id, conversation_id, seq, role, kind, body, status) "
                + "VALUES (?, ?, ?, ?, 'text', ?, ?)", id, conversationId, seq, role, body, status);
        jdbcTemplate.update("UPDATE chat_conversation SET next_seq = ? WHERE id = ?", seq + 1, conversationId);
        return id;
    }

    protected Map<String, Object> row(UUID messageId) {
        return jdbcTemplate.queryForMap("SELECT * FROM chat_message WHERE id = ?", messageId);
    }

    protected String status(UUID messageId) {
        return jdbcTemplate.queryForObject("SELECT status FROM chat_message WHERE id = ?", String.class, messageId);
    }

    protected int memberCount(UUID memberId) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(sum(count), 0) FROM chat_quota_usage "
                + "WHERE subject = ?", Integer.class, "member:" + memberId);
    }

    protected int globalCount() {
        return jdbcTemplate.queryForObject("SELECT COALESCE(sum(count), 0) FROM chat_quota_usage "
                + "WHERE subject = 'global'", Integer.class);
    }

    protected void setUsage(String subject, LocalDate day, int count) {
        jdbcTemplate.update("INSERT INTO chat_quota_usage (subject, day, count) VALUES (?, ?, ?) "
                + "ON CONFLICT (subject, day) DO UPDATE SET count = EXCLUDED.count", subject, day, count);
    }

    protected static LocalDate today() {
        return LocalDate.now(HCM);
    }

    protected int rows(String table, String column, UUID id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, id);
    }

    protected UserView member(String prefix) {
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", "Thành viên " + prefix,
                "MEMBER");
    }

    protected String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    protected static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
