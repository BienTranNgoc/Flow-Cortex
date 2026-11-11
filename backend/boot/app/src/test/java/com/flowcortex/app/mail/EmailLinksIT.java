package com.flowcortex.app.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.links.LinkList;
import com.flowcortex.mail.links.UnsubscribeLink;
import com.flowcortex.mail.links.UnsubscribeLinks;
import com.flowcortex.mail.links.UnsubscribeTokens;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The public email-link routes and the link store behind them. No catalog key is care or promotional yet,
 * so links are minted through {@link UnsubscribeLinks#forSend} directly.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.mail.link-secret=integration-test-link-secret-0123456789abcdef")
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
class EmailLinksIT extends AbstractPostgresBootTest {

    record Problem(int status, String detail) {
    }

    record View(boolean valid, String list, boolean unsubscribed, String maskedEmail) {
    }

    record Unsubscribed(boolean unsubscribed, String list, String maskedEmail) {
    }

    record OneClick(boolean ok) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private UnsubscribeLinks links;

    @Autowired
    private UnsubscribeTokens tokens;

    @Autowired
    private JdbcTemplate jdbc;

    private UserView admin;

    @BeforeEach
    void admin() {
        admin = userService.createInitialAdmin(email("links-admin"), "password123", "Admin");
    }

    @Test
    void concurrentFirstSendsCreateOneLinkRow() throws Exception {
        UserView member = member("links-race");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<UnsubscribeLink>> sends = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                sends.add(pool.submit(() -> {
                    start.await();
                    return links.forSend(member.id(), LinkList.CARE, MailKey.RESET_PASSWORD);
                }));
            }
            start.countDown();
            List<UUID> linkIds = new ArrayList<>();
            for (Future<UnsubscribeLink> send : sends) {
                linkIds.add(tokens.verify(tokenOf(send.get(30, TimeUnit.SECONDS))).orElseThrow().linkId());
            }

            assertThat(linkIds).hasSize(threads).containsOnly(linkIds.getFirst());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_unsubscribe_link WHERE user_id = ?",
                    Integer.class, member.id())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void viewingChangesNothingAndMasksTheAddress() {
        UserView member = member("links-view");
        String token = tokenOf(links.forSend(member.id(), LinkList.CARE, MailKey.RESET_PASSWORD));

        ResponseEntity<View> response = restTemplate.getForEntity("/api/v1/email-links/" + token, View.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new View(true, "care", false, member.email().charAt(0) + "***@example.com"));
        assertThat(jdbc.queryForObject("SELECT care_unsubscribed_at IS NULL FROM mail_unsubscribe_link "
                + "WHERE user_id = ?", Boolean.class, member.id())).isTrue();
        assertThat(historyRows(member)).isZero();
    }

    @Test
    void unsubscribeTwiceWritesOneHistoryRow() {
        UserView member = member("links-twice");
        String token = tokenOf(links.forSend(member.id(), LinkList.CARE, MailKey.RESET_PASSWORD));

        ResponseEntity<Unsubscribed> first = restTemplate.postForEntity(
                "/api/v1/email-links/" + token + "/unsubscribe?src=reset-password", null, Unsubscribed.class);
        ResponseEntity<Unsubscribed> second = restTemplate.postForEntity(
                "/api/v1/email-links/" + token + "/unsubscribe?src=reset-password", null, Unsubscribed.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getBody().unsubscribed()).isTrue();
        assertThat(historyRows(member)).isEqualTo(1);
        Map<String, Object> history = jdbc.queryForMap(
                "SELECT list, source, mail_key FROM mail_unsubscribe_history WHERE user_id = ?", member.id());
        assertThat(history).containsEntry("list", "care").containsEntry("source", "footer_link")
                .containsEntry("mail_key", "reset-password");
        assertThat(links.state(member.id()).careUnsubscribed()).isTrue();
        assertThat(links.state(member.id()).marketingUnsubscribed()).isFalse();
    }

    @Test
    void oneClickAcceptsAFormBodyAndNoBody() {
        UserView withForm = member("links-form");
        UserView withoutBody = member("links-nobody");
        String formToken = tokenOf(links.forSend(withForm.id(), LinkList.MARKETING, MailKey.RESET_PASSWORD));
        String bareToken = tokenOf(links.forSend(withoutBody.id(), LinkList.CARE, MailKey.RESET_PASSWORD));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<OneClick> form = restTemplate.postForEntity("/api/v1/email-links/" + formToken + "/one-click",
                new HttpEntity<>("List-Unsubscribe=One-Click", headers), OneClick.class);
        ResponseEntity<OneClick> bare = restTemplate.postForEntity("/api/v1/email-links/" + bareToken + "/one-click",
                null, OneClick.class);

        assertThat(form.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(form.getBody()).isEqualTo(new OneClick(true));
        assertThat(bare.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(links.state(withForm.id()).marketingUnsubscribed()).isTrue();
        assertThat(links.state(withoutBody.id()).careUnsubscribed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT source FROM mail_unsubscribe_history WHERE user_id = ?", String.class,
                withForm.id())).isEqualTo("one_click");
    }

    @Test
    void anUnknownSrcIsRecordedAsNoKey() {
        UserView member = member("links-src");
        String token = tokenOf(links.forSend(member.id(), LinkList.CARE, MailKey.RESET_PASSWORD));

        ResponseEntity<Unsubscribed> response = restTemplate.postForEntity(
                "/api/v1/email-links/" + token + "/unsubscribe?src=not-a-key", null, Unsubscribed.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT mail_key IS NULL FROM mail_unsubscribe_history WHERE user_id = ?",
                Boolean.class, member.id())).isTrue();
    }

    @Test
    void forgedUnknownAndOrphanedTokensAnswerTheSame404() {
        UserView gone = member("links-orphan");
        String orphan = tokenOf(links.forSend(gone.id(), LinkList.CARE, MailKey.RESET_PASSWORD));
        userService.deleteUser(admin.id(), gone.id());
        UserView live = member("links-forged");
        String valid = tokenOf(links.forSend(live.id(), LinkList.CARE, MailKey.RESET_PASSWORD));
        String forged = valid.substring(0, valid.length() - 2) + (valid.endsWith("AA") ? "BB" : "AA");
        String unknown = tokens.mint(UUID.randomUUID(), LinkList.CARE);

        List<ResponseEntity<Problem>> answers = new ArrayList<>();
        for (String token : List.of(forged, unknown, orphan, "garbage")) {
            answers.add(restTemplate.getForEntity("/api/v1/email-links/" + token, Problem.class));
            answers.add(restTemplate.postForEntity("/api/v1/email-links/" + token + "/unsubscribe", null,
                    Problem.class));
            answers.add(restTemplate.postForEntity("/api/v1/email-links/" + token + "/one-click", null,
                    Problem.class));
        }

        assertThat(answers).allSatisfy(answer -> assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(answers).extracting(ResponseEntity::getBody).containsOnly(answers.getFirst().getBody());
        assertThat(answers.getFirst().getBody().detail()).isEqualTo("This unsubscribe link is not valid");
        assertThat(historyRows(gone)).isZero();
    }

    @Test
    void theEleventhCallWithinTheWindowIs429AndAnotherTokenIsNot() {
        UserView member = member("links-limit");
        UserView other = member("links-limit-other");
        // The default Apache client sleeps through a 429's Retry-After and retries, which refills the bucket.
        TestRestTemplate noRetry = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .rootUri(restTemplate.getRootUri()));
        String token = tokenOf(links.forSend(member.id(), LinkList.CARE, MailKey.RESET_PASSWORD));
        String otherToken = tokenOf(links.forSend(other.id(), LinkList.CARE, MailKey.RESET_PASSWORD));

        for (int call = 0; call < 10; call++) {
            assertThat(restTemplate.getForEntity("/api/v1/email-links/" + token, View.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<Problem> refused = noRetry.getForEntity("/api/v1/email-links/" + token, Problem.class);
        ResponseEntity<Problem> refusedWrite = noRetry.postForEntity(
                "/api/v1/email-links/" + token + "/one-click", null, Problem.class);
        ResponseEntity<View> another = restTemplate.getForEntity("/api/v1/email-links/" + otherToken, View.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(Long.parseLong(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(refusedWrite.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(links.state(member.id()).careUnsubscribed()).isFalse();
        assertThat(another.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void linkActionLogsCarryNoAddressTokenOrClientAddress(CapturedOutput output) {
        UserView member = member("links-log");
        String token = tokenOf(links.forSend(member.id(), LinkList.CARE, MailKey.RESET_PASSWORD));

        restTemplate.getForEntity("/api/v1/email-links/" + token, View.class);
        restTemplate.postForEntity("/api/v1/email-links/" + token + "/unsubscribe", null, Unsubscribed.class);
        restTemplate.getForEntity("/api/v1/email-links/garbage", Problem.class);

        List<String> lines = output.getAll().lines().filter(line -> line.contains("mail.link_action")).toList();
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3);
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("outcome=viewed list=care"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("outcome=applied list=care"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("outcome=invalid"));
        assertThat(lines).allSatisfy(line -> {
            String event = line.substring(line.indexOf("mail.link_action"));
            assertThat(event).doesNotContain("@").doesNotContain(token).doesNotContain("127.0.0.1")
                    .doesNotContain("0:0:0:0:0:0:0:1");
        });
    }

    private UserView member(String prefix) {
        return userService.createUser(admin.id(), email(prefix), "password123", "Member", "MEMBER");
    }

    private int historyRows(UserView member) {
        return jdbc.queryForObject("SELECT count(*) FROM mail_unsubscribe_history WHERE user_id = ?", Integer.class,
                member.id());
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private static String tokenOf(UnsubscribeLink link) {
        String url = link.footerUrl();
        int from = url.indexOf("/email/unsubscribe/") + "/email/unsubscribe/".length();
        return url.substring(from, url.indexOf('?'));
    }
}
