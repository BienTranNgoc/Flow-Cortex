package com.flowcortex.app.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.app.realtime.SseTestClient.Open;
import com.flowcortex.realtime.api.RealtimeEvents;
import com.flowcortex.realtime.api.TopicAuthorizer;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/**
 * The realtime stream over real HTTP against the fully wired application: auth before any byte,
 * topic validity and authorization, the per-user cap, delivery through the broker, the heartbeat,
 * and the clean end at token expiry. Tokens are minted with the app's own encoder so the expiry
 * can be set to the second.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.realtime.heartbeat=1s",
        "flowcortex.realtime.max-streams-per-user=2"})
class RealtimeStreamIT extends AbstractPostgresBootTest {

    private static final Duration WAIT = SseTestClient.WAIT;

    @TestConfiguration
    static class ChatTopics {

        @Bean
        TopicAuthorizer chatAuthorizer() {
            return new TopicAuthorizer() {
                @Override
                public boolean supports(String topic) {
                    return topic.equals("chat:allowed") || topic.equals("chat:denied");
                }

                @Override
                public boolean authorize(UUID userId, String topic) {
                    return topic.equals("chat:allowed");
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserService userService;

    @Autowired
    private RealtimeEvents events;

    @Autowired
    private ApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Open> open = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeStreams() {
        open.forEach(Open::close);
    }

    private UserView newUser() {
        return userService.createInitialAdmin("rt-" + UUID.randomUUID() + "@example.com", "password123", "rt");
    }

    private String token(UUID subject, Instant expiresAt) {
        return SseTestClient.token(jwtEncoder, subject, expiresAt);
    }

    private String token(UserView user) {
        return token(user.id(), Instant.now().plus(Duration.ofMinutes(5)));
    }

    private Open open(String query, String token, String lastEventId) throws IOException, InterruptedException {
        Open stream = SseTestClient.open(http, port, query, token, lastEventId);
        open.add(stream);
        return stream;
    }

    @Test
    void aValidTokenOpensTheStreamAndAnEventPublishedThroughTheBrokerArrives() throws Exception {
        UserView user = newUser();
        Open stream = open("?topic=user", token(user), null);

        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.header("Content-Type")).startsWith("text/event-stream");
        assertThat(stream.header("Cache-Control")).isEqualTo("no-cache");
        assertThat(stream.header("X-Accel-Buffering")).isEqualTo("no");

        events.publish("user:" + user.id(), "run.updated", Map.of("runId", "r1"));

        stream.await(line -> line.equals("event: run.updated"));
        stream.await(line -> line.equals("data: {\"runId\":\"r1\"}"));
        assertThat(stream.seen()).anyMatch(line -> line.matches("id: [0-9a-f]+:\\d+"));
    }

    @Test
    void anEventForAnotherUsersTopicIsNotDelivered() throws Exception {
        UserView user = newUser();
        UserView other = newUser();
        Open stream = open("?topic=user", token(user), null);

        events.publish("user:" + other.id(), "run.updated", Map.of("runId", "other"));
        events.publish("user:" + user.id(), "run.updated", Map.of("runId", "mine"));

        stream.await(line -> line.startsWith("data: "));
        assertThat(stream.seen()).noneMatch(line -> line.contains("other"));
    }

    @Test
    void anIdleStreamReceivesAHeartbeatComment() throws Exception {
        Open stream = open("?topic=user", token(newUser()), null);

        stream.await(line -> line.equals(": ping"));
    }

    @Test
    void aReconnectNamingAnUnknownInstanceGetsOneResync() throws Exception {
        Open stream = open("?topic=user", token(newUser()), "someone-else:5");

        stream.await(line -> line.equals("event: resync"));
    }

    @Test
    void aMissingExpiredOrOrphanedTokenIsRefusedAsProblemJsonBeforeAnyStreamByte() throws Exception {
        UserView user = newUser();
        // The decoder tolerates 60 s of clock skew, so "expired" has to be well past that.
        String expired = token(user.id(), Instant.now().minusSeconds(300));
        String deletedUser = token(UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(5)));

        for (String token : new String[] {null, expired, deletedUser}) {
            Open refused = open("?topic=user", token, null);

            assertThat(refused.status()).as("token %s", token == null ? "missing" : "present").isEqualTo(401);
            assertThat(refused.header("Content-Type")).startsWith("application/problem+json");
            assertThat(refused.body()).contains("\"status\":401");
        }
    }

    @Test
    void aTopicNobodyClaimsOrAnEmptyListIsABadRequest() throws Exception {
        String token = token(newUser());

        for (String query : new String[] {"", "?topic=chat:unclaimed", "?topic=run:not-a-uuid", "?topic=user:42",
                "?topic=" + "a".repeat(129)}) {
            Open refused = open(query, token, null);

            assertThat(refused.status()).as(query).isEqualTo(400);
            assertThat(refused.header("Content-Type")).startsWith("application/problem+json");
        }
    }

    @Test
    void aClaimedTopicIsAuthorizedAndARefusedOneIsNotFoundForTheWholeRequest() throws Exception {
        String token = token(newUser());

        assertThat(open("?topic=chat:allowed", token, null).status()).isEqualTo(200);

        Open refused = open("?topic=user&topic=chat:denied", token, null);
        assertThat(refused.status()).isEqualTo(404);
        assertThat(refused.header("Content-Type")).startsWith("application/problem+json");
    }

    @Test
    void aStreamBeyondTheCapIsRefusedWith429AndASlotReturnsWhenOneCloses() throws Exception {
        String token = token(newUser());
        Open first = open("?topic=user", token, null);
        Open second = open("?topic=user", token, null);
        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);

        Open refused = open("?topic=user", token, null);
        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.header("Content-Type")).startsWith("application/problem+json");

        first.close();

        await().atMost(WAIT).until(() -> open("?topic=user", token, null).status() == 200);
    }

    @Test
    void aStreamEndsCleanlyAtTokenExpiryWithNoErrorAndNoProblemBytes(CapturedOutput output) throws Exception {
        UserView user = newUser();
        Open stream = open("?topic=user", token(user.id(), Instant.now().plusSeconds(3)), null);
        assertThat(stream.status()).isEqualTo(200);

        stream.awaitEnd();

        assertThat(stream.seen()).noneMatch(line -> line.contains("problem") || line.contains("\"status\""));
        assertThat(output.getAll()).doesNotContain("ERROR").doesNotContain("AccessDenied")
                .doesNotContain("AsyncRequestTimeout");
    }

    @Test
    void theApplicationsOwnPoolsStayAndTheModuleDeclaresNoExecutorBean() {
        assertThat(context.containsBean("taskScheduler")).isTrue();
        assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
        for (Class<?> type : new Class<?>[] {Executor.class, TaskScheduler.class, ScheduledExecutorService.class}) {
            for (String name : context.getBeanNamesForType(type)) {
                assertThat(context.getType(name).getName()).as(name).doesNotStartWith("com.flowcortex.realtime");
            }
        }
    }
}
