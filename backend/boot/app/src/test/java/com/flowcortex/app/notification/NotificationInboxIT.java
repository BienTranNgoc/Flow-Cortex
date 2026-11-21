package com.flowcortex.app.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.notification.api.NotificationRequested;
import com.flowcortex.user.api.UserActivatedEvent;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The inbox over real HTTP and the real event publication registry: delivery after commit, the cap,
 * owner-only reads, paging, read state and the live signal. The cap is 3 so trimming shows with a
 * handful of rows.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.notifications.max-per-member=3"})
@AutoConfigureTestRestTemplate
class NotificationInboxIT extends AbstractWorkflowHttpIT {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JwtEncoder jwtEncoder;

    @LocalServerPort
    private int port;

    record ItemView(UUID id, String type, String title, String body, String link, boolean read, Instant createdAt) {
    }

    record PageView(List<ItemView> items, int page, int size, boolean hasMore, long unreadCount) {
    }

    record CountView(long unreadCount) {
    }

    record MarkedView(int marked) {
    }

    record ProblemView(int status, String detail) {
    }

    @Test
    void aRolledBackProducerStoresNothing() throws Exception {
        UserView member = newUser("rollback");
        UUID id = UUID.randomUUID();

        try {
            transactions.executeWithoutResult(status -> {
                events.publishEvent(request(id, member.id()));
                throw new IllegalStateException("producer fails");
            });
        } catch (IllegalStateException expected) {
            // the producer's own failure
        }
        Thread.sleep(1000);

        assertThat(count(id)).isZero();
    }

    @Test
    void aRedeliveredRequestIsStoredOnce() {
        UserView member = newUser("redeliver");
        UUID id = UUID.randomUUID();

        publish(request(id, member.id()));
        publish(request(id, member.id()));
        awaitDelivered(id);

        assertThat(count(id)).isEqualTo(1);
        assertThat(outstandingPublications()).isZero();
    }

    @Test
    void aGoneRecipientIsDropped() throws Exception {
        UUID id = UUID.randomUUID();

        publish(request(id, UUID.randomUUID()));
        await().atMost(WAIT).until(() -> outstandingPublications() == 0);

        assertThat(count(id)).isZero();
    }

    @Test
    void aSuspendedRecipientIsStored() {
        UserView member = newUser("suspended");
        jdbc.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", member.id());
        UUID id = UUID.randomUUID();

        publish(request(id, member.id()));
        awaitDelivered(id);

        assertThat(count(id)).isEqualTo(1);
        assertThat(outstandingPublications()).isZero();
    }

    @Test
    void theInboxKeepsTheNewestUpToTheCap() {
        UserView member = newUser("cap");
        List<UUID> ids = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            publish(request(id, member.id()));
            awaitDelivered(id);
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ?", Long.class,
                member.id())).isEqualTo(3);
        assertThat(count(ids.get(4))).isEqualTo(1);
        assertThat(count(ids.get(0))).isZero();
    }

    @Test
    void trimmingLeavesOtherMembersAlone() {
        UserView other = newUser("untouched");
        seed(other.id(), "notification.welcome.title", Instant.now().minusSeconds(600), false);
        UserView member = newUser("trimmed");
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            publish(request(id, member.id()));
            awaitDelivered(id);
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ?", Long.class,
                other.id())).isEqualTo(1);
    }

    @Test
    void listsOwnNotificationsNewestFirstWithPaging() {
        UserView member = newUser("paging");
        UserView other = newUser("paging-other");
        Instant now = Instant.now();
        UUID oldest = seed(member.id(), "notification.welcome.title", now.minusSeconds(30), false);
        UUID middle = seed(member.id(), "notification.welcome.title", now.minusSeconds(20), false);
        UUID newest = seed(member.id(), "notification.welcome.title", now.minusSeconds(10), false);
        seed(other.id(), "notification.welcome.title", now, false);
        String token = loginAs(member);

        PageView first = get("/api/v1/notifications?page=0&size=2", token, PageView.class).getBody();
        PageView second = get("/api/v1/notifications?page=1&size=2", token, PageView.class).getBody();

        assertThat(first.items()).extracting(ItemView::id).containsExactly(newest, middle);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).extracting(ItemView::id).containsExactly(oldest);
        assertThat(second.hasMore()).isFalse();
        assertThat(first.unreadCount()).isEqualTo(3);
        assertThat(first.items().get(0).title()).isEqualTo("Welcome to Nodi");
        assertThat(first.items().get(0).read()).isFalse();
        assertThat(viReader(token).getBody().items().get(0).title()).isEqualTo("Chào mừng bạn đến với Nodi");
    }

    @Test
    void unreadFilterAndCount() {
        UserView member = newUser("unread");
        Instant now = Instant.now();
        seed(member.id(), "notification.welcome.title", now.minusSeconds(30), true);
        UUID second = seed(member.id(), "notification.welcome.title", now.minusSeconds(20), false);
        UUID third = seed(member.id(), "notification.welcome.title", now.minusSeconds(10), false);
        String token = loginAs(member);

        PageView unread = get("/api/v1/notifications?filter=unread", token, PageView.class).getBody();

        assertThat(unread.items()).extracting(ItemView::id).containsExactly(third, second);
        assertThat(unread.unreadCount()).isEqualTo(2);
        assertThat(get("/api/v1/notifications/unread-count", token, CountView.class).getBody().unreadCount())
                .isEqualTo(2);
    }

    @Test
    void invalidPagingIs400() {
        String token = loginAs(newUser("invalid"));

        for (String query : new String[] {"size=51", "size=0", "page=-1", "filter=archived", "page=abc"}) {
            ResponseEntity<ProblemView> response = get("/api/v1/notifications?" + query, token, ProblemView.class);

            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().detail()).as(query).contains(query.substring(0, query.indexOf('=')));
        }
    }

    @Test
    void markReadIsIdempotentAndForeignOrUnknownIdsAre404() {
        UserView member = newUser("mark");
        UserView other = newUser("mark-other");
        UUID own = seed(member.id(), "notification.welcome.title", Instant.now(), false);
        UUID foreign = seed(other.id(), "notification.welcome.title", Instant.now(), false);
        String token = loginAs(member);

        assertThat(post("/api/v1/notifications/" + own + "/read", token, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("/api/v1/notifications/" + own + "/read", token, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/v1/notifications/unread-count", token, CountView.class).getBody().unreadCount()).isZero();

        ProblemView foreignAnswer = post("/api/v1/notifications/" + foreign + "/read", token, null,
                ProblemView.class).getBody();
        ProblemView unknownAnswer = post("/api/v1/notifications/" + UUID.randomUUID() + "/read", token, null,
                ProblemView.class).getBody();
        ProblemView malformedAnswer = post("/api/v1/notifications/abc/read", token, null, ProblemView.class).getBody();
        assertThat(foreignAnswer.status()).isEqualTo(404);
        assertThat(foreignAnswer.detail()).isEqualTo("Notification not found")
                .isEqualTo(unknownAnswer.detail()).isEqualTo(malformedAnswer.detail());
        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM notification WHERE id = ?", Boolean.class,
                foreign)).isTrue();
    }

    @Test
    void readAllMarksOnlyTheCallers() {
        UserView member = newUser("readall");
        UserView other = newUser("readall-other");
        for (int i = 0; i < 3; i++) {
            seed(member.id(), "notification.welcome.title", Instant.now().minusSeconds(i), false);
        }
        seed(other.id(), "notification.welcome.title", Instant.now(), false);
        String token = loginAs(member);

        assertThat(post("/api/v1/notifications/read-all", token, null, MarkedView.class).getBody().marked())
                .isEqualTo(3);
        assertThat(post("/api/v1/notifications/read-all", token, null, MarkedView.class).getBody().marked()).isZero();

        assertThat(get("/api/v1/notifications/unread-count", token, CountView.class).getBody().unreadCount()).isZero();
        assertThat(get("/api/v1/notifications/unread-count", loginAs(other), CountView.class).getBody()
                .unreadCount()).isEqualTo(1);
    }

    @Test
    void aStoredNotificationSignalsTheUserStream() throws Exception {
        UserView member = newUser("signal");
        HttpRequest streamRequest = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/realtime/stream?topic=user"))
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + loginAs(member)).build();
        HttpResponse<java.io.InputStream> stream = HttpClient.newHttpClient()
                .send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
        List<String> lines = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException closedByTest) {
                // the test closes the stream when it is done
            }
        });
        reader.setDaemon(true);
        reader.start();

        try {
            UUID id = UUID.randomUUID();
            publish(request(id, member.id()));
            awaitDelivered(id);

            await().atMost(WAIT).untilAsserted(() -> assertThat(lines)
                    .anyMatch(line -> line.replace(" ", "").equals("event:notifications.changed")));
            assertThat(lines.stream().filter(line -> line.replace(" ", "").equals("event:notifications.changed"))
                    .count()).isEqualTo(1);
        } finally {
            stream.body().close();
        }
    }

    @Test
    void noTokenIs401AndADeletedUserIs404() {
        assertThat(get("/api/v1/notifications", null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String ghost = jwtEncoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("flow-cortex").issuedAt(Instant.now().minusSeconds(1))
                .expiresAt(Instant.now().plusSeconds(300)).subject(UUID.randomUUID().toString())
                .claim("role", "ADMIN").build())).getTokenValue();
        ResponseEntity<ProblemView> response = get("/api/v1/notifications/unread-count", ghost, ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().detail()).startsWith("Current user not found");
    }

    @Test
    void activationGivesOneWelcomeNotification() {
        UserView member = newUser("welcome");
        transactions.executeWithoutResult(status -> events.publishEvent(new UserActivatedEvent(member.id())));
        transactions.executeWithoutResult(status -> events.publishEvent(new UserActivatedEvent(member.id())));

        await().atMost(WAIT).until(() -> welcomeStored(member.id()));

        PageView page = get("/api/v1/notifications", loginAs(member), PageView.class).getBody();
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).type()).isEqualTo("welcome");
        assertThat(page.items().get(0).link()).isEqualTo("/workflows");
        assertThat(page.items().get(0).read()).isFalse();
    }

    /** Creating a member activates it, which stores a welcome notification; start each test from an empty inbox. */
    @Override
    protected UserView newUser(String prefix) {
        UserView created = super.newUser(prefix);
        UUID welcome = UUID.nameUUIDFromBytes(("welcome:" + created.id()).getBytes(StandardCharsets.UTF_8));
        await().atMost(WAIT).until(() -> count(welcome) == 1);
        jdbc.update("DELETE FROM notification WHERE user_id = ?", created.id());
        return created;
    }

    private ResponseEntity<PageView> viReader(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        return restTemplate.exchange("/api/v1/notifications", HttpMethod.GET, new HttpEntity<>(headers),
                PageView.class);
    }

    private static NotificationRequested request(UUID id, UUID memberId) {
        return new NotificationRequested(id, memberId, "test", "notification.welcome.title", null, List.of(), null);
    }

    private void publish(NotificationRequested request) {
        transactions.executeWithoutResult(status -> events.publishEvent(request));
    }

    private void awaitDelivered(UUID id) {
        await().atMost(WAIT).until(() -> count(id) == 1);
    }

    private long count(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE id = ?", Long.class, id);
    }

    private long outstandingPublications() {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL "
                + "AND event_type LIKE '%NotificationRequested%'", Long.class);
    }

    private boolean welcomeStored(UUID memberId) {
        return count(UUID.nameUUIDFromBytes(("welcome:" + memberId).getBytes(StandardCharsets.UTF_8))) == 1;
    }

    private UUID seed(UUID userId, String titleCode, Instant createdAt, boolean read) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notification (id, user_id, type, title_code, created_at, read_at) "
                + "VALUES (?, ?, 'test', ?, ?, ?)", id, userId, titleCode, Timestamp.from(createdAt),
                read ? Timestamp.from(createdAt) : null);
        return id;
    }
}
