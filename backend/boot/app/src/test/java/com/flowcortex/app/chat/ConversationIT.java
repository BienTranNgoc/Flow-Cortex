package com.flowcortex.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The conversation list, create, rename, delete and message read over HTTP, owner-scoped. */
@ChatIt
class ConversationIT extends AbstractPostgresBootTest {

    private static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");
    private static final String BASE = "/api/v1/chat/conversations";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserView admin;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record TitleRequest(String title) {
    }

    record Item(UUID id, String title, Instant lastActivityAt, Instant createdAt) {
    }

    record ListResponse(List<Item> items, boolean chatEnabled) {
    }

    record MessageView(UUID id, int seq, String role, String kind, String body, String status) {
    }

    record Problem(int status, URI type, String detail, String code, Map<String, String> errors) {
    }

    @BeforeEach
    void setUp() {
        admin = userService.createInitialAdmin(uniqueEmail("chat-admin"), "password123", "Chat Admin");
        jdbcTemplate.update("UPDATE chat_settings SET enabled = true WHERE id = 1");
    }

    @Test
    void theListIsTheCallersOwnNewestActivityFirstWithIdDescendingOnATie() {
        UserView owner = member("chat-list");
        UserView other = member("chat-list-other");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID oldest = conversation(owner.id(), "Oldest", now.minusSeconds(300));
        UUID tieA = conversation(owner.id(), "Tie A", now);
        UUID tieB = conversation(owner.id(), "Tie B", now);
        UUID newest = conversation(owner.id(), "Newest", now.plusSeconds(60));
        conversation(other.id(), "Not mine", now.plusSeconds(120));

        ResponseEntity<ListResponse> response = restTemplate.exchange(BASE, HttpMethod.GET,
                new HttpEntity<>(bearer(login(owner.email()))), ListResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().chatEnabled()).isTrue();
        // Postgres orders uuid by unsigned bytes, which matches the canonical text; UUID.compareTo is signed.
        List<UUID> tied = List.of(tieA, tieB).stream()
                .sorted(java.util.Comparator.comparing(UUID::toString).reversed()).toList();
        assertThat(response.getBody().items()).extracting(Item::id)
                .containsExactly(newest, tied.get(0), tied.get(1), oldest);
        assertThat(response.getBody().items().getFirst().title()).isEqualTo("Newest");
        assertThat(response.getBody().items().getFirst().createdAt()).isNotNull();
    }

    @Test
    void theListReportsTheConsoleSwitchAndStaysReadableWhenOff() {
        UserView owner = member("chat-switch");
        conversation(owner.id(), "Kept", Instant.now());
        jdbcTemplate.update("UPDATE chat_settings SET enabled = false WHERE id = 1");

        ListResponse body = restTemplate.exchange(BASE, HttpMethod.GET,
                new HttpEntity<>(bearer(login(owner.email()))), ListResponse.class).getBody();

        assertThat(body.chatEnabled()).isFalse();
        assertThat(body.items()).hasSize(1);
    }

    @Test
    void aConversationIsOnlyEverCreatedWithAFirstMessage() {
        UserView owner = member("chat-create");
        HttpHeaders keyed = bearer(login(owner.email()));
        keyed.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<Problem> noBody = restTemplate.exchange(BASE, HttpMethod.POST, new HttpEntity<>(keyed),
                Problem.class);
        ResponseEntity<Problem> noMessage = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(new TitleRequest(null), keyed), Problem.class);
        ResponseEntity<Problem> titleOnly = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(new TitleRequest("Kịch bản"), keyed), Problem.class);

        for (ResponseEntity<Problem> refused : List.of(noBody, noMessage, titleOnly)) {
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
            assertThat(refused.getBody().errors()).containsKey("text");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_conversation WHERE owner_id = ?",
                Integer.class, owner.id())).isZero();
    }

    @Test
    void renamingAcceptsOneToEightyCharactersAfterTrimAndRefusesTheRest() {
        UserView owner = member("chat-rename");
        String token = login(owner.email());
        UUID id = conversation(owner.id(), "Before", Instant.now());

        ResponseEntity<Item> renamed = rename(token, id, "  " + "a".repeat(80) + "  ");
        ResponseEntity<Problem> tooLong = restTemplate.exchange(BASE + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(new TitleRequest("a".repeat(81)), bearer(token)), Problem.class);
        ResponseEntity<Problem> blank = restTemplate.exchange(BASE + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(new TitleRequest("   "), bearer(token)), Problem.class);

        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().title()).isEqualTo("a".repeat(80));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
        assertThat(tooLong.getBody().errors()).containsKey("title");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody().errors()).containsKey("title");
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM chat_conversation WHERE id = ?", String.class, id))
                .isEqualTo("a".repeat(80));
    }

    @Test
    void deletingRemovesTheMessagesAndLeavesTheRunsTheConversationStarted() {
        UserView owner = member("chat-delete");
        String token = login(owner.email());
        UUID id = conversation(owner.id(), "Doomed", Instant.now());
        message(id, 1, "user", "hello");
        UUID runId = run(owner.id());

        ResponseEntity<Void> response = restTemplate.exchange(BASE + "/" + id, HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_conversation WHERE id = ?",
                Integer.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_message WHERE conversation_id = ?",
                Integer.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_run WHERE id = ?",
                Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void theMessagesComeInSequenceOrder() {
        UserView owner = member("chat-messages");
        UUID id = conversation(owner.id(), "Thread", Instant.now());
        message(id, 2, "assistant", "second");
        message(id, 1, "user", "first");

        ResponseEntity<MessageView[]> response = restTemplate.exchange(BASE + "/" + id + "/messages",
                HttpMethod.GET, new HttpEntity<>(bearer(login(owner.email()))), MessageView[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(MessageView::seq, MessageView::body)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "first"),
                        org.assertj.core.groups.Tuple.tuple(2, "second"));
        assertThat(response.getBody()[0].role()).isEqualTo("user");
        assertThat(response.getBody()[0].kind()).isEqualTo("text");
        assertThat(response.getBody()[0].status()).isEqualTo("done");
    }

    @Test
    void anotherMembersConversationAndAMissingOneAreTheSame404() {
        UserView owner = member("chat-owner");
        UserView intruder = member("chat-intruder");
        String token = login(intruder.email());
        UUID foreign = conversation(owner.id(), "Private", Instant.now());
        message(foreign, 1, "user", "secret");
        UUID missing = UUID.randomUUID();

        for (UUID id : List.of(foreign, missing)) {
            assertNotFound(restTemplate.exchange(BASE + "/" + id + "/messages", HttpMethod.GET,
                    new HttpEntity<>(bearer(token)), Problem.class));
            assertNotFound(restTemplate.exchange(BASE + "/" + id, HttpMethod.PUT,
                    new HttpEntity<>(new TitleRequest("Mine now"), bearer(token)), Problem.class));
            assertNotFound(restTemplate.exchange(BASE + "/" + id, HttpMethod.DELETE,
                    new HttpEntity<>(bearer(token)), Problem.class));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM chat_conversation WHERE id = ?",
                String.class, foreign)).isEqualTo("Private");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_message WHERE conversation_id = ?",
                Integer.class, foreign)).isEqualTo(1);
    }

    @Test
    void theNotFoundDetailIsWrittenInTheRequestLanguage() {
        HttpHeaders english = bearer(login(member("chat-lang").email()));
        english.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
        HttpHeaders vietnamese = bearer(login(member("chat-lang-vi").email()));
        vietnamese.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        String path = BASE + "/" + UUID.randomUUID() + "/messages";

        Problem inEnglish = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(english), Problem.class)
                .getBody();
        Problem inVietnamese = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(vietnamese),
                Problem.class).getBody();

        assertThat(inEnglish.detail()).isEqualTo("No such conversation for this caller");
        assertThat(inVietnamese.detail()).isNotEqualTo(inEnglish.detail()).isNotBlank();
    }

    @Test
    void theRoutesNeedASignedInMember() {
        assertThat(restTemplate.getForEntity(BASE, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void assertNotFound(ResponseEntity<Problem> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("chat-conversation-not-found"));
        assertThat(response.getBody().code()).isEqualTo("chat-conversation-not-found");
    }

    private ResponseEntity<Item> rename(String token, UUID id, String title) {
        return restTemplate.exchange(BASE + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(new TitleRequest(title), bearer(token)), Item.class);
    }

    private UUID conversation(UUID ownerId, String title, Instant lastActivity) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(lastActivity);
        jdbcTemplate.update("INSERT INTO chat_conversation (id, owner_id, title, created_at, updated_at, "
                + "last_activity_at) VALUES (?, ?, ?, ?, ?, ?)", id, ownerId, title, at, at, at);
        return id;
    }

    private void message(UUID conversationId, int seq, String role, String body) {
        jdbcTemplate.update("INSERT INTO chat_message (id, conversation_id, seq, role, kind, body, status) "
                + "VALUES (?, ?, ?, ?, 'text', ?, 'done')", UUID.randomUUID(), conversationId, seq, role, body);
    }

    private UUID run(UUID ownerId) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID workflowId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)"
                + " VALUES (?, ?, ?, false, ?, ?)", workflowId, ownerId, "Chat IT", now, now);
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at)"
                + " VALUES (?, ?, ?, 'running', ?)", runId, workflowId, ownerId, now);
        return runId;
    }

    private UserView member(String prefix) {
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", "Thành viên " + prefix,
                "MEMBER");
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
