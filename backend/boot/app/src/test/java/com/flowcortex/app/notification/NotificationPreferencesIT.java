package com.flowcortex.app.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.mail.links.LinkList;
import com.flowcortex.mail.links.LinkSource;
import com.flowcortex.mail.links.UnsubscribeLink;
import com.flowcortex.mail.links.UnsubscribeLinks;
import com.flowcortex.notification.api.NotificationRequested;
import com.flowcortex.user.api.UserView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The preferences routes over real HTTP, and the two receivers of the choices: delivery honours the in-app
 * flag, and the email switches are the mail list state the send path reads.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.mail.link-secret=integration-test-link-secret-0123456789abcdef")
@AutoConfigureTestRestTemplate
class NotificationPreferencesIT extends AbstractWorkflowHttpIT {

    private static final String PATH = "/api/v1/notifications/preferences";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private UnsubscribeLinks links;

    @Autowired
    private Mailer mailer;

    /** The default client cannot send a PATCH; the JDK one can. */
    private TestRestTemplate client;

    record PreferencesView(boolean inApp, boolean careEmail, boolean marketingEmail) {
    }

    record ProblemView(int status, String detail, Map<String, String> errors) {
    }

    record PageView(List<Map<String, Object>> items, int page, int size, boolean hasMore, long unreadCount) {
    }

    @BeforeEach
    void patchCapableClient() {
        client = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .rootUri(restTemplate.getRootUri()));
    }

    @Test
    void firstReadIsAllOn() {
        String token = loginAs(newUser("prefs-first"));

        ResponseEntity<PreferencesView> response = get(PATH, token, PreferencesView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new PreferencesView(true, true, true));
    }

    @Test
    void aPartialPatchReturnsTheFullSet() {
        UserView member = newUser("prefs-partial");
        String token = loginAs(member);

        ResponseEntity<PreferencesView> response = patch(token, Map.of("marketingEmail", false),
                PreferencesView.class, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new PreferencesView(true, true, false));
        assertThat(get(PATH, token, PreferencesView.class).getBody()).isEqualTo(new PreferencesView(true, true, false));
    }

    @Test
    void refusedBodiesWriteNothing() {
        UserView member = newUser("prefs-refused");
        String token = loginAs(member);
        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("inApp", null);
        Map<String, Object> unknownField = new LinkedHashMap<>();
        unknownField.put("inApp", false);
        unknownField.put("digest", false);
        Map<String, Map<String, Object>> bodies = new LinkedHashMap<>();
        bodies.put("body", Map.of());
        bodies.put("inApp", nullValue);
        bodies.put("careEmail", Map.of("careEmail", "no"));
        bodies.put("digest", unknownField);

        bodies.forEach((field, body) -> {
            ResponseEntity<ProblemView> response = patch(token, body, ProblemView.class, null);

            assertThat(response.getStatusCode()).as(field).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().errors()).as(field).containsOnlyKeys(field);
        });

        assertThat(get(PATH, token, PreferencesView.class).getBody()).isEqualTo(new PreferencesView(true, true, true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preference WHERE user_id = ?",
                Integer.class, member.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_unsubscribe_history WHERE user_id = ?",
                Integer.class, member.id())).isZero();
    }

    @Test
    void theRefusalReadsInTheCallersLanguage() {
        String token = loginAs(newUser("prefs-vi"));

        ProblemView english = patch(token, Map.of("digest", false), ProblemView.class, null).getBody();
        ProblemView vietnamese = patch(token, Map.of("digest", false), ProblemView.class, "vi").getBody();

        assertThat(english.errors().get("digest")).startsWith("digest is not a field");
        assertThat(vietnamese.errors().get("digest")).startsWith("digest không phải trường");
    }

    @Test
    void twoConcurrentPatchesOfDifferentFieldsBothLand() throws Exception {
        UserView member = newUser("prefs-race");
        String token = loginAs(member);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<PreferencesView>>> calls = new ArrayList<>();
            for (Map<String, Object> body : List.of(Map.<String, Object>of("inApp", false),
                    Map.<String, Object>of("careEmail", false))) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return patch(token, body, PreferencesView.class, null);
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<PreferencesView>> call : calls) {
                assertThat(call.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(get(PATH, token, PreferencesView.class).getBody()).isEqualTo(new PreferencesView(false, false, true));
    }

    @Test
    void inAppOffSuppressesAndOnAgainResumesWithoutBackfill() {
        UserView member = newUser("prefs-inapp");
        String token = loginAs(member);
        UUID stored = UUID.randomUUID();
        publish(request(stored, member.id()));
        await().atMost(WAIT).until(() -> count(stored) == 1);

        patch(token, Map.of("inApp", false), PreferencesView.class, null);
        UUID whileOff = UUID.randomUUID();
        publish(request(whileOff, member.id()));
        await().atMost(WAIT).until(() -> outstandingPublications() == 0);

        assertThat(count(whileOff)).isZero();
        assertThat(count(stored)).isEqualTo(1);
        assertThat(get("/api/v1/notifications", token, PageView.class).getBody().items()).hasSize(1);

        patch(token, Map.of("inApp", true), PreferencesView.class, null);
        UUID afterOn = UUID.randomUUID();
        publish(request(afterOn, member.id()));
        await().atMost(WAIT).until(() -> count(afterOn) == 1);

        assertThat(count(whileOff)).isZero();
        assertThat(get("/api/v1/notifications", token, PageView.class).getBody().items()).hasSize(2);
    }

    @Test
    void emailSwitchesWriteTheMailListState() {
        UserView member = newUser("prefs-mail");
        String token = loginAs(member);

        patch(token, Map.of("marketingEmail", false), PreferencesView.class, null);
        assertThat(links.state(member.id()).marketingUnsubscribed()).isTrue();
        assertThat(links.state(member.id()).careUnsubscribed()).isFalse();
        assertThat(history(member)).containsExactly("marketing/preferences/unsubscribe");

        patch(token, Map.of("marketingEmail", false), PreferencesView.class, null);
        assertThat(history(member)).hasSize(1);

        patch(token, Map.of("marketingEmail", true), PreferencesView.class, null);
        assertThat(links.state(member.id()).marketingUnsubscribed()).isFalse();
        assertThat(history(member)).containsExactly("marketing/preferences/unsubscribe",
                "marketing/preferences/resubscribe");

        UnsubscribeLink footer = links.forSend(member.id(), LinkList.CARE, MailKey.RESET_PASSWORD);
        String url = footer.footerUrl();
        String linkToken = url.substring(url.indexOf("/email/unsubscribe/") + "/email/unsubscribe/".length(),
                url.indexOf('?'));
        links.apply(linkToken, LinkSource.FOOTER_LINK, null);
        assertThat(get(PATH, token, PreferencesView.class).getBody()).isEqualTo(new PreferencesView(true, false, true));
    }

    @Test
    void aPasswordResetMailStillLeavesWithBothSwitchesOff() {
        UserView member = newUser("prefs-reset");
        patch(loginAs(member), Map.of("careEmail", false, "marketingEmail", false), PreferencesView.class, null);

        MailOutcome outcome = mailer.send(new OutgoingMail(MailKey.RESET_PASSWORD, member.id(), member.email(),
                Locale.ENGLISH, List.of("Member", "https://example.test/reset")));

        assertThat(outcome).isNotEqualTo(MailOutcome.SUPPRESSED);
    }

    private <T> ResponseEntity<T> patch(String token, Map<String, Object> body, Class<T> type, String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return client.exchange(PATH, HttpMethod.PATCH, new HttpEntity<>(body, headers), type);
    }

    private List<String> history(UserView member) {
        return jdbc.queryForList("SELECT list || '/' || source || '/' || action FROM mail_unsubscribe_history "
                + "WHERE user_id = ? ORDER BY created_at, id", String.class, member.id());
    }

    private static NotificationRequested request(UUID id, UUID memberId) {
        return new NotificationRequested(id, memberId, "test", "notification.welcome.title", null, List.of(), null);
    }

    private void publish(NotificationRequested request) {
        transactions.executeWithoutResult(status -> events.publishEvent(request));
    }

    private long count(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE id = ?", Long.class, id);
    }

    private long outstandingPublications() {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL "
                + "AND event_type LIKE '%NotificationRequested%'", Long.class);
    }

    /** Creating a member stores a welcome notification; start each test from an empty inbox. */
    @Override
    protected UserView newUser(String prefix) {
        UserView created = super.newUser(prefix);
        UUID welcome = UUID.nameUUIDFromBytes(("welcome:" + created.id()).getBytes(StandardCharsets.UTF_8));
        await().atMost(WAIT).until(() -> count(welcome) == 1);
        jdbc.update("DELETE FROM notification WHERE user_id = ?", created.id());
        return created;
    }
}
