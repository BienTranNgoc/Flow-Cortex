package com.flowcortex.app.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.llm.api.LlmChatClient;
import com.flowcortex.llm.api.LlmChatOutcome;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A turn's deltas and its end signal over the real realtime stream, with the real chat event binding and
 * only the provider scripted: the stream authorizes {@code chat:<id>} through the chat module's own claim.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.chat.enabled=true")
@AutoConfigureTestRestTemplate
class ChatStreamIT extends AbstractPostgresBootTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedProvider {

        @Bean
        @Primary
        LlmChatClient scriptedChatClient() {
            return (request, listener) -> {
                listener.onText("Hello ");
                listener.onText("stream");
                return new LlmChatOutcome.Finished("stub-model", 3, 2, List.of());
            };
        }
    }

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record SendRequest(String text, List<String> attachments) {
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Stream> open = new CopyOnWriteArrayList<>();

    @BeforeEach
    void enableChat() {
        jdbcTemplate.update("UPDATE chat_settings SET enabled = true, updated_by = NULL WHERE id = 1");
        jdbcTemplate.update("DELETE FROM chat_quota_usage WHERE subject = 'global'");
    }

    @AfterEach
    void closeStreams() {
        open.forEach(Stream::close);
    }

    @Test
    void aTurnsDeltasAndEndSignalReachTheOwnersChatStream() throws Exception {
        UserView owner = member("stream-owner");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id());
        Stream stream = open("?topic=chat:" + conversation, token);
        assertThat(stream.status).isEqualTo(200);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        assertThat(restTemplate.exchange("/api/v1/chat/conversations/" + conversation + "/messages",
                HttpMethod.POST, new HttpEntity<>(new SendRequest("Chào", null), headers), String.class)
                .getStatusCode().value()).isEqualTo(202);

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(stream.lines).contains("event:chat.delta", "event:chat.updated");
            assertThat(String.join("\n", stream.lines)).contains("\"text\":\"Hello \"")
                    .contains("\"text\":\"stream\"").contains("\"offset\":6");
        });
    }

    @Test
    void aForeignOrMissingConversationIsRefusedAsNotFoundAndAMalformedOneAsBadRequest() throws Exception {
        UserView owner = member("stream-owner");
        UserView other = member("stream-other");
        UUID foreign = conversation(owner.id());
        String otherToken = login(other.email());

        assertThat(open("?topic=chat:" + foreign, otherToken).status).isEqualTo(404);
        assertThat(open("?topic=chat:" + UUID.randomUUID(), otherToken).status).isEqualTo(404);
        assertThat(open("?topic=chat:not-a-uuid", otherToken).status).isEqualTo(400);
        assertThat(open("?topic=chat:" + foreign.toString().toUpperCase(), otherToken).status).isEqualTo(400);
    }

    private UserView member(String prefix) {
        UserView admin = userService.createInitialAdmin(prefix + "-admin-" + UUID.randomUUID() + "@example.com",
                "password123", "Stream Admin");
        return userService.createUser(admin.id(), prefix + "-" + UUID.randomUUID() + "@example.com", "password123",
                "Thành viên " + prefix, "MEMBER");
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private UUID conversation(UUID ownerId) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO chat_conversation (id, owner_id, title, created_at, updated_at, "
                + "last_activity_at) VALUES (?, ?, ?, ?, ?, ?)", id, ownerId, "Stream", at, at, at);
        return id;
    }

    private Stream open(String query, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/v1/realtime/stream" + query))
                .header("Accept", "text/event-stream").header("Authorization", "Bearer " + token).build();
        Stream stream = new Stream(http.send(request, HttpResponse.BodyHandlers.ofInputStream()));
        open.add(stream);
        return stream;
    }

    /** One stream response: the status at once, the lines as they arrive (the space after a colon dropped). */
    private static final class Stream {

        private final HttpResponse<java.io.InputStream> response;
        private final int status;
        private final List<String> lines = new CopyOnWriteArrayList<>();

        Stream(HttpResponse<java.io.InputStream> response) {
            this.response = response;
            this.status = response.statusCode();
            Thread reader = new Thread(this::read, "it-chat-sse-reader");
            reader.setDaemon(true);
            reader.start();
        }

        private void read() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line.replaceFirst("^(event|data|id): ", "$1:"));
                }
            } catch (IOException closedByTest) {
                // The test closes the stream when it is done with it.
            }
        }

        void close() {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
