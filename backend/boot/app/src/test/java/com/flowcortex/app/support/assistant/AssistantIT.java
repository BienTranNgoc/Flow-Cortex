package com.flowcortex.app.support.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.llm.api.LlmClient;
import com.flowcortex.llm.api.LlmJsonRequest;
import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The assistant over HTTP with a fake provider. Every case starts from an empty knowledge base, no
 * conversations, no quota usage and the console switch on.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.support.assistant.enabled=true",
        "flowcortex.support.contact-email=hotro@nodi.vn"})
@AutoConfigureTestRestTemplate
@Import(AssistantIT.FakeLlmConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AssistantIT extends AbstractPostgresBootTest {

    private static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String NO_ANSWER = "Mình chưa có câu trả lời cho câu hỏi này";
    private static final String REFUSAL = "Mình không thể trả lời câu hỏi này";
    private static final String CREDIT_QUESTION = "Làm sao nạp credit?";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeLlm fakeLlm;

    @Autowired
    private MeterRegistry meters;

    @Value("${flowcortex.support.assistant.hash-secret}")
    private String hashSecret;

    private UserView admin;
    private String adminToken;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record GroupRequest(String name) {
    }

    record IdView(UUID id) {
    }

    record ArticleRequest(UUID groupId, String slug, String title, String body, Integer featuredPosition) {
    }

    record AskRequest(UUID conversationId, String question) {
    }

    record Source(String slug, String title) {
    }

    record Message(String role, String text, String outcome, List<Source> sources) {
    }

    record Quota(int remaining, Instant resetsAt) {
    }

    record Reply(UUID conversationId, Message message, Quota quota) {
    }

    record HistoryMessage(String role, String text, String outcome, List<Source> sources, Instant createdAt) {
    }

    record Conversation(UUID conversationId, List<HistoryMessage> messages) {
    }

    record SettingsUpdate(Boolean assistantEnabled) {
    }

    record AdminSettings(boolean assistantEnabled, String updatedBy, Instant updatedAt, boolean envEnabled,
            boolean ceilingReachedToday, int dailyCeiling) {
    }

    record Problem(int status, URI type, String detail, Instant retryAt) {
    }

    @BeforeEach
    void emptyAssistant() {
        jdbcTemplate.update("DELETE FROM support_ai_conversation");
        jdbcTemplate.update("DELETE FROM support_quota_usage");
        jdbcTemplate.update("DELETE FROM support_article");
        jdbcTemplate.update("DELETE FROM support_topic_group");
        jdbcTemplate.update("UPDATE support_settings SET assistant_enabled = true, updated_by = NULL WHERE id = 1");
        fakeLlm.reset();
        admin = userService.createInitialAdmin(uniqueEmail("as-admin"), "password123", "Assistant Admin");
        adminToken = login(admin.email());
        UUID group = createGroup("Credit");
        publish(createArticle(group, "nap-credit", "Nạp credit", "Vào Hồ sơ > Credit để nạp credit."));
        publish(createArticle(group, "xoa-tai-khoan", "Xoá tài khoản", "Vào Cài đặt rồi chọn mục cuối."));
    }

    @Test
    void anAnswerListsTheArticleItCameFrom() {
        fakeLlm.answer = request -> answered("Vào **Hồ sơ > Credit**.", "nap-credit");
        String member = memberToken("as-answer");

        ResponseEntity<Reply> response = ask(member, null, CREDIT_QUESTION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Reply reply = response.getBody();
        assertThat(reply.conversationId()).isNotNull();
        assertThat(reply.message().role()).isEqualTo("assistant");
        assertThat(reply.message().outcome()).isEqualTo("answered");
        assertThat(reply.message().text()).isEqualTo("Vào **Hồ sơ > Credit**.");
        assertThat(reply.message().sources()).containsExactly(new Source("nap-credit", "Nạp credit"));
        assertThat(reply.quota().remaining()).isEqualTo(49);
        assertThat(reply.quota().resetsAt()).isEqualTo(nextMidnight());
    }

    @Test
    void aCitedArticleOutsideTheRetrievedSetIsDropped() {
        fakeLlm.answer = request -> answered("Vào Hồ sơ.", "nap-credit", "xoa-tai-khoan");

        Reply reply = ask(memberToken("as-cited"), null, CREDIT_QUESTION).getBody();

        assertThat(fakeLlm.requests.getFirst().userText()).doesNotContain("xoa-tai-khoan");
        assertThat(reply.message().sources()).containsExactly(new Source("nap-credit", "Nạp credit"));
    }

    @Test
    void anEnglishQuestionFindsTheVietnameseArticleAndAsksForAnEnglishAnswer() {
        fakeLlm.answer = request -> answered("Open Profile > Credit to top up.", "nap-credit");
        HttpHeaders headers = bearer(memberToken("as-english"));
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en");

        ResponseEntity<Reply> response = restTemplate.exchange("/api/v1/support/assistant/messages", HttpMethod.POST,
                new HttpEntity<>(new AskRequest(null, "Why am I told I don't have enough credit?"), headers),
                Reply.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message().sources()).containsExactly(new Source("nap-credit", "Nạp credit"));
        LlmJsonRequest sent = fakeLlm.requests.getFirst();
        assertThat(sent.userText()).contains("[nap-credit]");
        assertThat(sent.systemInstruction()).contains("tiếng Anh (English)").doesNotContain("Trả lời bằng tiếng Việt");
    }

    @Test
    void aProblemDetailIsWrittenInTheRequestLanguage() {
        UUID conversation = UUID.randomUUID();
        HttpHeaders vietnamese = bearer(memberToken("as-problem-lang"));
        vietnamese.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        HttpHeaders english = new HttpHeaders();
        english.putAll(vietnamese);
        english.set(HttpHeaders.ACCEPT_LANGUAGE, "en");

        Problem inVietnamese = restTemplate.exchange("/api/v1/support/assistant/messages", HttpMethod.POST,
                new HttpEntity<>(new AskRequest(conversation, CREDIT_QUESTION), vietnamese), Problem.class).getBody();
        Problem inEnglish = restTemplate.exchange("/api/v1/support/assistant/messages", HttpMethod.POST,
                new HttpEntity<>(new AskRequest(conversation, CREDIT_QUESTION), english), Problem.class).getBody();

        assertThat(inVietnamese.type()).isEqualTo(PROBLEMS.resolve("conversation-not-found"));
        assertThat(inVietnamese.detail()).isEqualTo("Không tìm thấy cuộc trò chuyện này");
        assertThat(inEnglish.detail()).isEqualTo("No such conversation for this caller");
    }

    @Test
    void noMatchingArticleAnswersWithoutTheProviderAndIsNotCounted() {
        ResponseEntity<Reply> response = ask(memberToken("as-nomatch"), null, "Đổi ngôn ngữ ở đâu?");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message().outcome()).isEqualTo("no_answer");
        assertThat(response.getBody().message().text()).isEqualTo(NO_ANSWER);
        assertThat(response.getBody().message().sources()).isEmpty();
        assertThat(response.getBody().quota().remaining()).isEqualTo(50);
        assertThat(fakeLlm.requests).isEmpty();
        assertThat(quotaRows()).isZero();
    }

    @Test
    void theProviderSayingTheArticlesDoNotAnswerIsANoAnswerThatCounts() {
        fakeLlm.answer = request -> new LlmJsonResult.Answered(
                "{\"answered\":false,\"answer\":\"\",\"citedSlugs\":[\"nap-credit\"]}", "fake", 10, 2);
        UserView member = member("as-notanswered");

        Reply reply = ask(login(member.email()), null, CREDIT_QUESTION).getBody();

        assertThat(reply.message().outcome()).isEqualTo("no_answer");
        assertThat(reply.message().text()).isEqualTo(NO_ANSWER);
        assertThat(reply.message().sources()).isEmpty();
        assertThat(count("member:" + member.id())).isEqualTo(1);
    }

    @Test
    void aGuestsEleventhQuestionIsRefusedUntilMidnight() {
        String session = UUID.randomUUID().toString();
        assertThat(guestAsk(session, null, CREDIT_QUESTION).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbcTemplate.update("UPDATE support_quota_usage SET count = 10 WHERE subject_key LIKE 'guest-session:%'");

        ResponseEntity<Problem> response = guestAskForProblem(session, CREDIT_QUESTION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("assistant-quota"));
        assertThat(response.getBody().retryAt()).isEqualTo(nextMidnight());
        assertThat(fakeLlm.requests).hasSize(1);
    }

    @Test
    void aNewSessionOnABusyNetworkIsAnswered() {
        setNetworkCount(40);

        ResponseEntity<Reply> response = guestAsk(UUID.randomUUID().toString(), null, CREDIT_QUESTION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().quota().remaining()).isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count FROM support_quota_usage WHERE subject_key LIKE 'guest-ip:%'", Integer.class))
                .isEqualTo(41);
    }

    @Test
    void theNetworksHundredAndFirstQuestionIsRefused() {
        setNetworkCount(100);

        ResponseEntity<Problem> response = guestAskForProblem(UUID.randomUUID().toString(), CREDIT_QUESTION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("assistant-quota"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM support_quota_usage WHERE subject_key LIKE 'guest-session:%' AND count > 0",
                Integer.class)).isZero();
    }

    @Test
    void aMembersEleventhQuestionInAMinuteIsRefusedAndNotCounted() {
        UserView member = member("as-burst");
        String token = login(member.email());
        for (int i = 0; i < 10; i++) {
            assertThat(ask(token, null, CREDIT_QUESTION).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<Problem> response = askForProblem(token, CREDIT_QUESTION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("assistant-quota"));
        assertThat(response.getBody().retryAt()).isAfter(Instant.now()).isBefore(Instant.now().plusSeconds(61));
        assertThat(count("member:" + member.id())).isEqualTo(10);
    }

    @Test
    void theCeilingPausesTheAssistantWithOneWarningForTheDay(CapturedOutput output) {
        jdbcTemplate.update("INSERT INTO support_quota_usage (subject_key, day, count) VALUES ('global', ?, 2000)",
                today());
        String member = memberToken("as-ceiling");

        ResponseEntity<Problem> first = askForProblem(member, CREDIT_QUESTION);
        ResponseEntity<Problem> second = askForProblem(member, CREDIT_QUESTION);
        ResponseEntity<Problem> noMatch = askForProblem(member, "Đổi ngôn ngữ ở đâu?");

        for (ResponseEntity<Problem> response : List.of(first, second, noMatch)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("assistant-paused"));
        }
        assertThat(fakeLlm.requests).isEmpty();
        assertThat(output.getAll().split("support.assistant.ceiling outcome=reached", -1)).hasSize(2);
        assertThat(adminSettings().ceilingReachedToday()).isTrue();
        assertThat(adminSettings().dailyCeiling()).isEqualTo(2000);
    }

    @Test
    void theConsoleSwitchStopsTheNextQuestionAndRecordsWhoFlippedIt() {
        String member = memberToken("as-console");

        ResponseEntity<AdminSettings> put = restTemplate.exchange("/api/v1/admin/support/settings", HttpMethod.PUT,
                new HttpEntity<>(new SettingsUpdate(false), bearer(adminToken)), AdminSettings.class);
        ResponseEntity<Problem> refused = askForProblem(member, CREDIT_QUESTION);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getBody().type()).isEqualTo(PROBLEMS.resolve("assistant-disabled"));
        assertThat(fakeLlm.requests).isEmpty();
        AdminSettings settings = adminSettings();
        assertThat(settings.assistantEnabled()).isFalse();
        assertThat(settings.updatedBy()).isEqualTo(admin.email());
        assertThat(settings.updatedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(settings.envEnabled()).isTrue();
        assertThat(settings.ceilingReachedToday()).isFalse();
    }

    @Test
    void historyStaysReadableWithTheConsoleSwitchOff() {
        String member = memberToken("as-console-history");
        String session = UUID.randomUUID().toString();
        UUID memberConversation = ask(member, null, CREDIT_QUESTION).getBody().conversationId();
        UUID guestConversation = guestAsk(session, null, CREDIT_QUESTION).getBody().conversationId();
        restTemplate.exchange("/api/v1/admin/support/settings", HttpMethod.PUT,
                new HttpEntity<>(new SettingsUpdate(false), bearer(adminToken)), AdminSettings.class);

        assertThat(memberLatest(restTemplate, member).getBody().conversationId()).isEqualTo(memberConversation);
        assertThat(guestLatest(restTemplate, session).getBody().conversationId()).isEqualTo(guestConversation);
    }

    @Test
    void aMemberCannotFlipTheConsoleSwitch() {
        ResponseEntity<Problem> response = restTemplate.exchange("/api/v1/admin/support/settings", HttpMethod.PUT,
                new HttpEntity<>(new SettingsUpdate(false), bearer(memberToken("as-notadmin"))), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adminSettings().assistantEnabled()).isTrue();
    }

    @Test
    void tenParallelGuestQuestionsAtNineGetExactlyOneAnswer() throws Exception {
        String session = UUID.randomUUID().toString();
        assertThat(guestAsk(session, null, CREDIT_QUESTION).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbcTemplate.update("UPDATE support_quota_usage SET count = 9 WHERE subject_key LIKE 'guest-session:%'");
        fakeLlm.reset();

        List<HttpStatus> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<ResponseEntity<String>>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(pool.submit(() -> restTemplate.exchange("/api/v1/support/assistant/guest/messages",
                        HttpMethod.POST, new HttpEntity<>(new AskRequest(null, CREDIT_QUESTION), session(session)),
                        String.class)));
            }
            for (Future<ResponseEntity<String>> call : calls) {
                statuses.add(HttpStatus.valueOf(call.get().getStatusCode().value()));
            }
        }

        assertThat(statuses).filteredOn(HttpStatus.OK::equals).hasSize(1);
        assertThat(statuses).filteredOn(HttpStatus.TOO_MANY_REQUESTS::equals).hasSize(9);
        assertThat(fakeLlm.requests).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count FROM support_quota_usage WHERE subject_key LIKE 'guest-session:%'", Integer.class))
                .isEqualTo(10);
    }

    @Test
    void aProviderTimeoutIsUnavailableAndNotCounted() {
        fakeLlm.answer = request -> new LlmJsonResult.Failed(LlmJsonResult.Kind.TIMEOUT);
        UserView member = member("as-timeout");

        ResponseEntity<Problem> response = askForProblem(login(member.email()), CREDIT_QUESTION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("assistant-unavailable"));
        assertThat(count("member:" + member.id())).isZero();
        assertThat(count("global")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_ai_message", Integer.class)).isZero();
    }

    @Test
    void aBlockedAnswerIsTheFixedRefusalAndCounts() {
        fakeLlm.answer = request -> new LlmJsonResult.Blocked("SAFETY");
        UserView member = member("as-blocked");

        Reply reply = ask(login(member.email()), null, CREDIT_QUESTION).getBody();

        assertThat(reply.message().outcome()).isEqualTo("blocked");
        assertThat(reply.message().text()).isEqualTo(REFUSAL);
        assertThat(reply.message().sources()).isEmpty();
        assertThat(count("member:" + member.id())).isEqualTo(1);
    }

    @Test
    void aMemberReturnsToYesterdaysConversationAndContinuesIt() {
        String member = memberToken("as-history");
        UUID conversation = ask(member, null, CREDIT_QUESTION).getBody().conversationId();
        jdbcTemplate.update("UPDATE support_ai_conversation SET last_message_at = last_message_at - interval '1 day'");
        jdbcTemplate.update("UPDATE support_ai_message SET created_at = created_at - interval '1 day'");

        ResponseEntity<Conversation> latest = restTemplate.exchange("/api/v1/support/assistant/conversations/latest",
                HttpMethod.GET, new HttpEntity<>(bearer(member)), Conversation.class);

        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latest.getBody().conversationId()).isEqualTo(conversation);
        assertThat(latest.getBody().messages()).extracting(HistoryMessage::role).containsExactly("user", "assistant");
        assertThat(latest.getBody().messages().getFirst().text()).isEqualTo(CREDIT_QUESTION);
        assertThat(latest.getBody().messages().get(1).sources())
                .containsExactly(new Source("nap-credit", "Nạp credit"));

        Reply continued = ask(member, conversation, "Nạp credit bằng thẻ được không?").getBody();

        assertThat(continued.conversationId()).isEqualTo(conversation);
        assertThat(restTemplate.exchange("/api/v1/support/assistant/conversations/latest", HttpMethod.GET,
                new HttpEntity<>(bearer(member)), Conversation.class).getBody().messages()).hasSize(4);
    }

    @Test
    void anotherMembersConversationIsNotFound() {
        UUID conversation = ask(memberToken("as-owner"), null, CREDIT_QUESTION).getBody().conversationId();
        String other = memberToken("as-other");

        ResponseEntity<Problem> continued = restTemplate.exchange("/api/v1/support/assistant/messages",
                HttpMethod.POST, new HttpEntity<>(new AskRequest(conversation, CREDIT_QUESTION), bearer(other)),
                Problem.class);
        ResponseEntity<Problem> latest = restTemplate.exchange("/api/v1/support/assistant/conversations/latest",
                HttpMethod.GET, new HttpEntity<>(bearer(other)), Problem.class);

        assertThat(continued.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(continued.getBody().type()).isEqualTo(PROBLEMS.resolve("conversation-not-found"));
        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fakeLlm.requests).hasSize(1);
    }

    @Test
    void oneGuestSessionCannotReadAnother() {
        String sessionA = UUID.randomUUID().toString();
        String sessionB = UUID.randomUUID().toString();
        UUID conversation = guestAsk(sessionA, null, CREDIT_QUESTION).getBody().conversationId();

        ResponseEntity<Problem> latestB = restTemplate.exchange(
                "/api/v1/support/assistant/guest/conversations/latest", HttpMethod.GET,
                new HttpEntity<>(session(sessionB)), Problem.class);
        ResponseEntity<Problem> continuedByB = restTemplate.exchange("/api/v1/support/assistant/guest/messages",
                HttpMethod.POST, new HttpEntity<>(new AskRequest(conversation, CREDIT_QUESTION), session(sessionB)),
                Problem.class);
        ResponseEntity<Conversation> latestA = restTemplate.exchange(
                "/api/v1/support/assistant/guest/conversations/latest", HttpMethod.GET,
                new HttpEntity<>(session(sessionA)), Conversation.class);

        assertThat(latestB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(continuedByB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(continuedByB.getBody().type()).isEqualTo(PROBLEMS.resolve("conversation-not-found"));
        assertThat(latestA.getBody().conversationId()).isEqualTo(conversation);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_ai_conversation "
                + "WHERE guest_session_hash IN (?, ?)", Integer.class, sessionA, sessionB)).isZero();
    }

    @Test
    void aSessionHeaderThatIsNotAVersionFourUuidIsRefused() {
        ResponseEntity<Problem> missing = restTemplate.exchange("/api/v1/support/assistant/guest/messages",
                HttpMethod.POST, new HttpEntity<>(new AskRequest(null, CREDIT_QUESTION)), Problem.class);
        ResponseEntity<Problem> notV4 = guestAskForProblem("6ba7b810-9dad-11d1-80b4-00c04fd430c8", CREDIT_QUESTION);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(notV4.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fakeLlm.requests).isEmpty();
    }

    @Test
    void theProviderGetsOnlyTheQuestionAndTheArticles(CapturedOutput output) throws Exception {
        UserView member = member("as-privacy");
        String question = "Tôi muốn nạp credit cho tài khoản";
        ask(login(member.email()), null, question);
        String session = UUID.randomUUID().toString();
        guestAsk(session, null, question);

        List<String> subjects = jdbcTemplate.queryForList("SELECT subject_key FROM support_quota_usage",
                String.class);
        List<String> hashes = subjects.stream().filter(key -> key.startsWith("guest-"))
                .map(key -> key.substring(key.indexOf(':') + 1)).toList();
        assertThat(hashes).hasSize(2).allMatch(hash -> hash.matches("[0-9a-f]{64}"))
                .doesNotContain(sha256(session), sha256("127.0.0.1"), sha256("0:0:0:0:0:0:0:1"));
        assertThat(fakeLlm.requests).hasSize(2);
        for (LlmJsonRequest request : fakeLlm.requests) {
            String sent = request.systemInstruction() + request.userText();
            assertThat(request.userText()).contains(question).contains("Vào Hồ sơ > Credit để nạp credit.");
            assertThat(sent).doesNotContain(member.email(), member.name(), member.id().toString(), session,
                    "127.0.0.1", "0:0:0:0:0:0:0:1", hashes.get(0), hashes.get(1));
        }
        assertThat(output.getAll()).contains("support.assistant outcome=answered")
                .contains("length=" + question.length())
                .doesNotContain(question);
    }

    @Test
    void aQuestionOverAThousandCharactersIsRefused() {
        String member = memberToken("as-long");

        ResponseEntity<Problem> tooLong = askForProblem(member, "a".repeat(1001));
        ResponseEntity<Reply> longest = ask(member, null, "  " + "a".repeat(1000) + "  ");
        ResponseEntity<Problem> blank = askForProblem(member, "   ");

        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody().type()).isEqualTo(PROBLEMS.resolve("question-too-long"));
        assertThat(longest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fakeLlm.requests).isEmpty();
    }

    @Test
    void aSuspendedMemberIsForbidden() {
        UserView member = member("as-suspended");
        String token = login(member.email());
        userService.updateUser(admin.id(), member.id(), member.email(), member.name(), "MEMBER", "SUSPENDED");

        ResponseEntity<Problem> response = askForProblem(token, CREDIT_QUESTION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fakeLlm.requests).isEmpty();
    }

    @Test
    void eachCallIsCountedByOutcomeAndLoggedWithoutTheQuestion(CapturedOutput output) {
        UUID group = jdbcTemplate.queryForObject("SELECT id FROM support_topic_group", UUID.class);
        publish(createArticle(group, "goi-credit", "Gói credit", "Mua gói credit bằng thẻ."));
        fakeLlm.answer = request -> answered("Có hai cách.", "nap-credit", "goi-credit");
        Counter answered = meters.counter("support.assistant.outcome", "outcome", "answered");
        double before = answered.count();

        Reply reply = ask(memberToken("as-telemetry"), null, CREDIT_QUESTION).getBody();

        assertThat(reply.message().sources()).hasSize(2);
        assertThat(answered.count()).isEqualTo(before + 1);
        assertThat(output.getAll()).contains("support.assistant outcome=answered sources=2")
                .doesNotContain(CREDIT_QUESTION);
    }

    @Nested
    @TestPropertySource(properties = "flowcortex.support.assistant.enabled=false")
    class AssistantSwitchedOff {

        @Autowired
        private TestRestTemplate nestedRestTemplate;

        @Autowired
        private FakeLlm nestedFakeLlm;

        @Test
        void everyAssistantRouteIsDisabledAndTheProviderIsNeverCalled() {
            nestedFakeLlm.reset();

            ResponseEntity<Problem> member = nestedRestTemplate.exchange("/api/v1/support/assistant/messages",
                    HttpMethod.POST, new HttpEntity<>(new AskRequest(null, CREDIT_QUESTION)), Problem.class);
            ResponseEntity<Problem> guest = nestedRestTemplate.exchange("/api/v1/support/assistant/guest/messages",
                    HttpMethod.POST, new HttpEntity<>(new AskRequest(null, CREDIT_QUESTION),
                            session(UUID.randomUUID().toString())), Problem.class);

            for (ResponseEntity<Problem> response : List.of(member, guest)) {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("assistant-disabled"));
            }
            assertThat(nestedFakeLlm.requests).isEmpty();
        }

        /** Questions are refused here, so the conversations are written as earlier answers left them. */
        @Test
        void historyStaysReadable() throws Exception {
            UserView member = member("as-env-history");
            String session = UUID.randomUUID().toString();
            UUID memberConversation = storedConversation("owner_user_id", member.id());
            UUID guestConversation = storedConversation("guest_session_hash", hmac(session));

            ResponseEntity<Conversation> memberHistory = memberLatest(nestedRestTemplate, login(member.email()));
            ResponseEntity<Conversation> guestHistory = guestLatest(nestedRestTemplate, session);

            assertThat(memberHistory.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(memberHistory.getBody().conversationId()).isEqualTo(memberConversation);
            assertThat(guestHistory.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(guestHistory.getBody().conversationId()).isEqualTo(guestConversation);
        }
    }

    private UUID storedConversation(String ownerColumn, Object owner) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO support_ai_conversation (id, " + ownerColumn
                + ", last_message_at, created_at, updated_at) VALUES (?, ?, now(), now(), now())", id, owner);
        jdbcTemplate.update("INSERT INTO support_ai_message (id, conversation_id, role, text, outcome, cited_slugs, "
                + "created_at) VALUES (?, ?, 'user', ?, NULL, '{}', now())", UUID.randomUUID(), id, CREDIT_QUESTION);
        return id;
    }

    /** The guest key as the backend computes it under the test hash secret. */
    private String hmac(String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static ResponseEntity<Conversation> memberLatest(TestRestTemplate client, String token) {
        return client.exchange("/api/v1/support/assistant/conversations/latest", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Conversation.class);
    }

    private static ResponseEntity<Conversation> guestLatest(TestRestTemplate client, String session) {
        return client.exchange("/api/v1/support/assistant/guest/conversations/latest", HttpMethod.GET,
                new HttpEntity<>(session(session)), Conversation.class);
    }

    /** Stands in for the provider: records every request and answers with {@link #answer}. */
    static final class FakeLlm implements LlmClient {

        final List<LlmJsonRequest> requests = new CopyOnWriteArrayList<>();
        volatile Function<LlmJsonRequest, LlmJsonResult> answer;

        FakeLlm() {
            reset();
        }

        void reset() {
            requests.clear();
            answer = request -> answered("Vào Hồ sơ > Credit.", "nap-credit");
        }

        @Override
        public LlmJsonResult generateJson(LlmJsonRequest request) {
            requests.add(request);
            return answer.apply(request);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeLlmConfig {

        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    private static LlmJsonResult answered(String answer, String... slugs) {
        String cited = String.join(",", Arrays.stream(slugs).map(slug -> "\"" + slug + "\"").toList());
        return new LlmJsonResult.Answered("{\"answered\":true,\"answer\":\"" + answer + "\",\"citedSlugs\":["
                + cited + "]}", "fake-model", 120, 30);
    }

    private ResponseEntity<Reply> ask(String token, UUID conversationId, String question) {
        return restTemplate.exchange("/api/v1/support/assistant/messages", HttpMethod.POST,
                new HttpEntity<>(new AskRequest(conversationId, question), bearer(token)), Reply.class);
    }

    private ResponseEntity<Problem> askForProblem(String token, String question) {
        return restTemplate.exchange("/api/v1/support/assistant/messages", HttpMethod.POST,
                new HttpEntity<>(new AskRequest(null, question), bearer(token)), Problem.class);
    }

    private ResponseEntity<Reply> guestAsk(String session, UUID conversationId, String question) {
        return restTemplate.exchange("/api/v1/support/assistant/guest/messages", HttpMethod.POST,
                new HttpEntity<>(new AskRequest(conversationId, question), session(session)), Reply.class);
    }

    private ResponseEntity<Problem> guestAskForProblem(String session, String question) {
        return restTemplate.exchange("/api/v1/support/assistant/guest/messages", HttpMethod.POST,
                new HttpEntity<>(new AskRequest(null, question), session(session)), Problem.class);
    }

    /** The network's key is only known hashed, so one answered guest question creates its row first. */
    private void setNetworkCount(int count) {
        guestAsk(UUID.randomUUID().toString(), null, CREDIT_QUESTION);
        jdbcTemplate.update("DELETE FROM support_quota_usage WHERE subject_key NOT LIKE 'guest-ip:%'");
        jdbcTemplate.update("UPDATE support_quota_usage SET count = ? WHERE subject_key LIKE 'guest-ip:%'", count);
        fakeLlm.reset();
    }

    private AdminSettings adminSettings() {
        ResponseEntity<AdminSettings> response = restTemplate.exchange("/api/v1/admin/support/settings",
                HttpMethod.GET, new HttpEntity<>(bearer(adminToken)), AdminSettings.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private int count(String subject) {
        List<Integer> counts = jdbcTemplate.queryForList(
                "SELECT count FROM support_quota_usage WHERE subject_key = ? AND day = ?", Integer.class, subject,
                today());
        return counts.isEmpty() ? 0 : counts.getFirst();
    }

    private int quotaRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM support_quota_usage", Integer.class);
    }

    private static LocalDate today() {
        return LocalDate.now(VIETNAM);
    }

    private static Instant nextMidnight() {
        return today().plusDays(1).atStartOfDay(VIETNAM).toInstant();
    }

    private UserView member(String prefix) {
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", "Thành viên " + prefix,
                "MEMBER");
    }

    private String memberToken(String prefix) {
        return login(member(prefix).email());
    }

    private UUID createGroup(String name) {
        ResponseEntity<IdView> response = restTemplate.exchange("/api/v1/admin/support/kb/groups", HttpMethod.POST,
                new HttpEntity<>(new GroupRequest(name), bearer(adminToken)), IdView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private UUID createArticle(UUID group, String slug, String title, String body) {
        ResponseEntity<IdView> response = restTemplate.exchange("/api/v1/admin/support/kb/articles", HttpMethod.POST,
                new HttpEntity<>(new ArticleRequest(group, slug, title, body, null), bearer(adminToken)),
                IdView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void publish(UUID article) {
        assertThat(restTemplate.exchange("/api/v1/admin/support/kb/articles/" + article + "/publish", HttpMethod.POST,
                new HttpEntity<>(bearer(adminToken)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static HttpHeaders session(String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Support-Session", session);
        return headers;
    }

    private static String sha256(String value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
