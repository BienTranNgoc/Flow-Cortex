package com.flowcortex.app.chat;

import static com.flowcortex.app.chat.ChatTestConfig.StubChatClient.finished;
import static com.flowcortex.app.chat.ChatTestConfig.StubChatClient.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.user.api.UserView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A turn's deltas and its end signal over the real realtime stream: the stub provider is scripted, and the
 * recording event seam forwards to the real chat event binding, whose topic the stream authorizes through the
 * chat module's own claim.
 */
@ChatIt
class ChatStreamIT extends ChatItSupport {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Stream> open = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeStreams() {
        open.forEach(Stream::close);
    }

    @Test
    void aTurnsDeltasAndEndSignalReachTheOwnersChatStream() throws Exception {
        UserView owner = member("stream-owner");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Stream");
        stub.script(finished(), text("Hello "), text("stream"));
        Stream stream = open("?topic=chat:" + conversation, token);
        assertThat(stream.status).isEqualTo(200);

        sendOk(token, conversation, "Chào");

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
        UUID foreign = conversation(owner.id(), "Stream");
        String otherToken = login(other.email());

        assertThat(open("?topic=chat:" + foreign, otherToken).status).isEqualTo(404);
        assertThat(open("?topic=chat:" + UUID.randomUUID(), otherToken).status).isEqualTo(404);
        assertThat(open("?topic=chat:not-a-uuid", otherToken).status).isEqualTo(400);
        assertThat(open("?topic=chat:" + foreign.toString().toUpperCase(), otherToken).status).isEqualTo(400);
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
