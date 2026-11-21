package com.flowcortex.app.chat;

import static com.flowcortex.app.chat.ChatTestConfig.StubChatClient.failed;
import static com.flowcortex.app.chat.ChatTestConfig.StubChatClient.finished;
import static com.flowcortex.app.chat.ChatTestConfig.StubChatClient.hold;
import static com.flowcortex.app.chat.ChatTestConfig.StubChatClient.signal;
import static com.flowcortex.app.chat.ChatTestConfig.StubChatClient.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.llm.api.LlmChatOutcome;
import com.flowcortex.llm.api.LlmChatRequest;
import com.flowcortex.llm.api.LlmTool;
import com.flowcortex.llm.api.LlmTurn;
import com.flowcortex.user.api.UserView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The send, stream, stop, retry and refuse paths of a chat turn over HTTP with a scripted provider. */
@ChatIt
@ExtendWith(OutputCaptureExtension.class)
class ChatTurnIT extends ChatItSupport {

    private static final Duration WAIT = Duration.ofSeconds(8);

    @Autowired
    private ChatProperties properties;

    record FirstMessage(String text, List<Att> attachments) {
    }

    record CreateRequest(String title, FirstMessage firstMessage) {
    }

    record Created(UUID id, String title, Msg userMessage, UUID assistantMessageId) {
    }

    @Test
    void aSendStoresTheMessageChargesTheQuotaStreamsTheAnswerAndEndsDoneWithoutLinks() throws Exception {
        UserView owner = member("turn-happy");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        CountDownLatch emitted = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        stub.script(finished(), text("Hello "), text("[site](https://evil.example) world"), signal(emitted),
                hold(gate));
        int globalBefore = globalCount();

        ResponseEntity<Sent> response = send(token, conversation, UUID.randomUUID().toString(),
                "  Làm video cho quán cà phê  ", Sent.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Sent sent = response.getBody();
        assertThat(sent.userMessage().body()).isEqualTo("Làm video cho quán cà phê");
        assertThat(sent.userMessage().role()).isEqualTo("user");
        assertThat(emitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(row(sent.userMessage().id())).containsEntry("status", "done").containsEntry("seq", 1);
        assertThat(row(sent.assistantMessageId())).containsEntry("status", "streaming")
                .containsEntry("role", "assistant").containsEntry("seq", 2)
                .containsEntry("charge_day", java.sql.Date.valueOf(today()))
                .doesNotContainEntry("turn_deadline", null);
        assertThat(memberCount(owner.id())).isEqualTo(1);
        assertThat(globalCount()).isEqualTo(globalBefore + 1);

        gate.countDown();
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(sent.assistantMessageId())).isEqualTo("done"));
        awaitIdle();
        assertThat(row(sent.assistantMessageId())).containsEntry("body", "Hello site world");
        assertThat(events.deltasOf(sent.assistantMessageId())).extracting(d -> d.offset(), d -> d.text())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, "Hello "),
                        org.assertj.core.groups.Tuple.tuple(6, "[site](https://evil.example) world"));
        assertThat(events.updatesOf(sent.assistantMessageId())).isEqualTo(1);
        assertThat(memberCount(owner.id())).isEqualTo(1);
    }

    @Test
    void memberTextReachesTheModelInsideDelimitersWithTheMarkersStrippedInTheRequestLanguage() {
        UserView owner = member("turn-prompt");
        UUID conversation = conversation(owner.id(), "Thread");
        Sent sent = sendOk(login(owner.email()), conversation, "a <<<MESSAGE>>> b >>> c");
        awaitStatus(sent.assistantMessageId(), "done");

        LlmChatRequest request = stub.requests.getFirst();
        LlmTurn last = request.turns().getLast();
        assertThat(last).isInstanceOf(LlmTurn.User.class);
        String wrapped = ((LlmTurn.User) last).text();
        assertThat(wrapped).startsWith("<<<MESSAGE>>>\n").endsWith("\n<<<END>>>");
        String inner = wrapped.substring("<<<MESSAGE>>>\n".length(), wrapped.length() - "\n<<<END>>>".length());
        assertThat(inner).doesNotContain("<<<").doesNotContain(">>>").contains("MESSAGE b").contains("c");
        assertThat(request.systemInstruction()).isNotBlank();
        assertThat(request.tools()).extracting(LlmTool::name)
                .containsExactly("find_workflows", "propose_run", "run_status");
        assertThat(request.language()).isEqualTo("vi");
        assertThat(request.model()).isEqualTo(properties.model());
    }

    @Test
    void aMemberWritingEnglishGetsAnEnglishPrompt() {
        UserView owner = member("turn-english");
        UUID conversation = conversation(owner.id(), "Thread");
        var headers = idempotent(login(owner.email()), UUID.randomUUID().toString());
        headers.set("Accept-Language", "en");

        Sent sent = restTemplate.exchange(BASE + "/" + conversation + "/messages", HttpMethod.POST,
                new HttpEntity<>(new SendRequest("hello", null), headers), Sent.class).getBody();
        awaitStatus(sent.assistantMessageId(), "done");

        assertThat(stub.requests.getFirst().language()).isEqualTo("en");
    }

    @Test
    void thePromptCarriesTheLastTwentyMessagesStartingWithAMemberTurn() {
        UserView owner = member("turn-history");
        UUID conversation = conversation(owner.id(), "Thread");
        for (int seq = 1; seq <= 30; seq++) {
            seedMessage(conversation, seq, seq % 2 == 1 ? "user" : "assistant", "m" + seq, "done");
        }
        Sent sent = sendOk(login(owner.email()), conversation, "newest");
        awaitStatus(sent.assistantMessageId(), "done");

        List<LlmTurn> turns = stub.requests.getFirst().turns();
        assertThat(turns).hasSize(19);
        assertThat(turns.getFirst()).isInstanceOf(LlmTurn.User.class);
        assertThat(((LlmTurn.User) turns.getFirst()).text()).contains("m13");
        assertThat(((LlmTurn.User) turns.getLast()).text()).contains("newest");
        assertThat(turns.get(1)).isEqualTo(new LlmTurn.Model("m14"));
    }

    @Test
    void afterAFailedAnswerTheNextPromptKeepsBothMemberMessagesInOneTurn() {
        UserView owner = member("turn-merge");
        UUID conversation = conversation(owner.id(), "Thread");
        seedMessage(conversation, 1, "user", "first question", "done");
        seedMessage(conversation, 2, "assistant", "", "failed");
        Sent sent = sendOk(login(owner.email()), conversation, "second question");
        awaitStatus(sent.assistantMessageId(), "done");

        List<LlmTurn> turns = stub.requests.getFirst().turns();
        assertThat(turns).hasSize(1);
        String merged = ((LlmTurn.User) turns.getFirst()).text();
        assertThat(merged).contains("first question").contains("second question");
    }

    @Test
    void aSecondSendWhileATurnRunsIsRefusedAndStoresAndChargesNothing() throws Exception {
        UserView owner = member("turn-busy");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        CountDownLatch emitted = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        stub.script(finished(), text("working"), signal(emitted), hold(gate));
        Sent first = sendOk(token, conversation, "one");
        assertThat(emitted.await(5, TimeUnit.SECONDS)).isTrue();
        String secondKey = UUID.randomUUID().toString();
        int globalBefore = globalCount();

        ResponseEntity<Problem> second = send(token, conversation, secondKey, "two", Problem.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().type()).isEqualTo(PROBLEMS.resolve("chat-turn-in-progress"));
        assertThat(rows("chat_message", "conversation_id", conversation)).isEqualTo(2);
        assertThat(memberCount(owner.id())).isEqualTo(1);
        assertThat(globalCount()).isEqualTo(globalBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_idempotency WHERE owner_id = ? "
                + "AND key = ?", Integer.class, owner.id(), secondKey)).isZero();
        gate.countDown();
        awaitStatus(first.assistantMessageId(), "done");
    }

    @Test
    void theSameKeyAndBodyReturnsTheFirstResultAndStartsNothingAndADifferentBodyIsRefused() {
        UserView owner = member("turn-idem");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        String key = UUID.randomUUID().toString();
        Sent first = send(token, conversation, key, "same text", Sent.class).getBody();
        awaitStatus(first.assistantMessageId(), "done");
        awaitIdle();

        ResponseEntity<Sent> repeat = send(token, conversation, key, "same text", Sent.class);
        ResponseEntity<Problem> different = send(token, conversation, key, "other text", Problem.class);

        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(repeat.getBody().assistantMessageId()).isEqualTo(first.assistantMessageId());
        assertThat(repeat.getBody().userMessage().id()).isEqualTo(first.userMessage().id());
        assertThat(different.getStatusCode().value()).isEqualTo(422);
        assertThat(different.getBody().type()).isEqualTo(PROBLEMS.resolve("idempotency-key-reused"));
        assertThat(rows("chat_message", "conversation_id", conversation)).isEqualTo(2);
        assertThat(stub.requests).hasSize(1);
        assertThat(memberCount(owner.id())).isEqualTo(1);
    }

    @Test
    void twoRequestsRacingWithOneKeyStartOneTurnAndBothAnswerTheSameResult() throws Exception {
        UserView owner = member("turn-race");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<Sent>>> calls = List.of(
                    pool.submit(() -> raced(start, token, conversation, key)),
                    pool.submit(() -> raced(start, token, conversation, key)));
            start.countDown();
            ResponseEntity<Sent> a = calls.get(0).get(15, TimeUnit.SECONDS);
            ResponseEntity<Sent> b = calls.get(1).get(15, TimeUnit.SECONDS);

            assertThat(a.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(b.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(a.getBody().assistantMessageId()).isEqualTo(b.getBody().assistantMessageId());
            assertThat(a.getBody().userMessage().id()).isEqualTo(b.getBody().userMessage().id());
            awaitStatus(a.getBody().assistantMessageId(), "done");
            assertThat(rows("chat_message", "conversation_id", conversation)).isEqualTo(2);
            assertThat(memberCount(owner.id())).isEqualTo(1);
            assertThat(stub.requests).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void thePartialAnswerIsCheckpointedWhileStreamingSoAReloadShowsIt() throws Exception {
        UserView owner = member("turn-checkpoint");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        CountDownLatch emitted = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        stub.script(finished(), text("x".repeat(500)), signal(emitted), hold(gate));
        Sent sent = sendOk(token, conversation, "long answer please");
        assertThat(emitted.await(5, TimeUnit.SECONDS)).isTrue();

        await().atMost(WAIT).untilAsserted(() -> {
            Msg[] reload = restTemplate.exchange(BASE + "/" + conversation + "/messages", HttpMethod.GET,
                    new HttpEntity<>(bearer(token)), Msg[].class).getBody();
            Msg answer = reload[reload.length - 1];
            assertThat(answer.status()).isEqualTo("streaming");
            assertThat(answer.body()).hasSize(500);
        });
        gate.countDown();
        awaitStatus(sent.assistantMessageId(), "done");
    }

    @Test
    void stoppingKeepsTheTextSoFarMarksTheAnswerStoppedAndKeepsTheCharge() throws Exception {
        UserView owner = member("turn-stop");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        CountDownLatch emitted = new CountDownLatch(1);
        stub.script(finished(), text("partial text"), signal(emitted), hold(new CountDownLatch(1)));
        Sent sent = sendOk(token, conversation, "go");
        assertThat(emitted.await(5, TimeUnit.SECONDS)).isTrue();
        UserView stranger = member("turn-stop-other");

        ResponseEntity<Problem> foreign = restTemplate.exchange(stopUrl(conversation, sent), HttpMethod.POST,
                new HttpEntity<>(bearer(login(stranger.email()))), Problem.class);
        ResponseEntity<Void> stop = restTemplate.exchange(stopUrl(conversation, sent), HttpMethod.POST,
                new HttpEntity<>(bearer(token)), Void.class);

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stop.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitStatus(sent.assistantMessageId(), "stopped");
        awaitIdle();
        assertThat(row(sent.assistantMessageId())).containsEntry("body", "partial text");
        assertThat(events.updatesOf(sent.assistantMessageId())).isEqualTo(1);
        assertThat(memberCount(owner.id())).isEqualTo(1);

        ResponseEntity<Void> late = restTemplate.exchange(stopUrl(conversation, sent), HttpMethod.POST,
                new HttpEntity<>(bearer(token)), Void.class);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(status(sent.assistantMessageId())).isEqualTo("stopped");
    }

    @Test
    void aProviderFailureMarksTheAnswerFailedKeepsTheMessageAndRefundsTheStoredDay() {
        UserView owner = member("turn-fail");
        UUID conversation = conversation(owner.id(), "Thread");
        int globalBefore = globalCount();
        stub.script(failed(), text("partial"));

        Sent sent = sendOk(login(owner.email()), conversation, "will fail");
        awaitStatus(sent.assistantMessageId(), "failed");
        awaitIdle();

        assertThat(memberCount(owner.id())).isZero();
        assertThat(globalCount()).isEqualTo(globalBefore);
        assertThat(errorCode(sent.assistantMessageId())).isEqualTo("upstream");
        assertThat(row(sent.userMessage().id())).containsEntry("body", "will fail").containsEntry("status", "done");
        assertThat(events.updatesOf(sent.assistantMessageId())).isEqualTo(1);
    }

    @Test
    void aBlockedAnswerIsStoredFailedWithItsErrorCodeAndRefunded() {
        UserView owner = member("turn-blocked");
        UUID conversation = conversation(owner.id(), "Thread");
        stub.script(new LlmChatOutcome.Blocked("SAFETY"));

        Sent sent = sendOk(login(owner.email()), conversation, "blocked please");
        awaitStatus(sent.assistantMessageId(), "failed");
        awaitIdle();

        assertThat(errorCode(sent.assistantMessageId())).isEqualTo("blocked");
        assertThat(memberCount(owner.id())).isZero();
    }

    @Test
    void aRunnerThatLosesTheTerminalUpdateToTheSweeperNeitherRefundsNorPublishes() throws Exception {
        UserView owner = member("turn-lost");
        UUID conversation = conversation(owner.id(), "Thread");
        CountDownLatch emitted = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        stub.script(finished(), text("late text"), signal(emitted), hold(gate));
        Sent sent = sendOk(login(owner.email()), conversation, "slow");
        assertThat(emitted.await(5, TimeUnit.SECONDS)).isTrue();
        jdbcTemplate.update("UPDATE chat_message SET status = 'failed', body = 'swept' WHERE id = ?",
                sent.assistantMessageId());

        gate.countDown();
        awaitIdle();

        assertThat(row(sent.assistantMessageId())).containsEntry("status", "failed").containsEntry("body", "swept");
        assertThat(events.updatesOf(sent.assistantMessageId())).isZero();
        assertThat(memberCount(owner.id())).isEqualTo(1);
    }

    @Test
    void retryingAFailedAnswerReusesTheMemberMessageAndChargesAgain() {
        UserView owner = member("turn-retry");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        stub.script(failed());
        Sent first = sendOk(token, conversation, "try me");
        awaitStatus(first.assistantMessageId(), "failed");
        awaitIdle();
        assertThat(memberCount(owner.id())).isZero();

        ResponseEntity<Sent> retried = retry(token, conversation, first.assistantMessageId(), Sent.class);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retried.getBody().userMessage().id()).isEqualTo(first.userMessage().id());
        assertThat(retried.getBody().assistantMessageId()).isNotEqualTo(first.assistantMessageId());
        awaitStatus(retried.getBody().assistantMessageId(), "done");
        awaitIdle();
        assertThat(memberCount(owner.id())).isEqualTo(1);
        assertThat(rows("chat_message", "conversation_id", conversation)).isEqualTo(3);
        assertThat(row(first.assistantMessageId())).containsEntry("status", "failed");
        List<LlmTurn> turns = stub.requests.get(1).turns();
        assertThat(turns).hasSize(1);
        assertThat(((LlmTurn.User) turns.getFirst()).text()).contains("try me");

        ResponseEntity<Problem> superseded = retry(token, conversation, first.assistantMessageId(), Problem.class);

        assertThat(superseded.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(superseded.getBody().code()).isEqualTo("chat-turn-not-retryable");
        assertThat(memberCount(owner.id())).isEqualTo(1);
        assertThat(rows("chat_message", "conversation_id", conversation)).isEqualTo(3);
    }

    @Test
    void retryingAStoppedAnswerIsAllowedButADoneOrUnknownOneIsRefused() throws Exception {
        UserView owner = member("turn-retry-guard");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        Sent done = sendOk(token, conversation, "finish");
        awaitStatus(done.assistantMessageId(), "done");
        awaitIdle();
        CountDownLatch emitted = new CountDownLatch(1);
        stub.script(finished(), text("half"), signal(emitted), hold(new CountDownLatch(1)));
        Sent stopped = sendOk(token, conversation, "stop me");
        assertThat(emitted.await(5, TimeUnit.SECONDS)).isTrue();
        restTemplate.exchange(stopUrl(conversation, stopped), HttpMethod.POST, new HttpEntity<>(bearer(token)),
                Void.class);
        awaitStatus(stopped.assistantMessageId(), "stopped");
        awaitIdle();

        ResponseEntity<Problem> notRetryable = retry(token, conversation, done.assistantMessageId(), Problem.class);
        ResponseEntity<Problem> unknown = retry(token, conversation, UUID.randomUUID(), Problem.class);
        ResponseEntity<Sent> again = retry(token, conversation, stopped.assistantMessageId(), Sent.class);

        assertThat(notRetryable.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notRetryable.getBody().code()).isEqualTo("chat-turn-not-retryable");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.getBody().userMessage().id()).isEqualTo(stopped.userMessage().id());
        awaitStatus(again.getBody().assistantMessageId(), "done");
    }

    @Test
    void creatingWithAFirstMessageStoresBothAndTitlesTheConversationWithItsFirstSixtyCharacters() {
        UserView owner = member("turn-create");
        String token = login(owner.email());
        String longText = "Hãy làm một video quảng cáo cho quán cà phê của tôi ở Đà Nẵng với phong cách trẻ trung";
        String key = UUID.randomUUID().toString();

        ResponseEntity<Created> created = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(new CreateRequest(null, new FirstMessage(longText, null)), idempotent(token, key)),
                Created.class);
        ResponseEntity<Created> replay = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(new CreateRequest(null, new FirstMessage(longText, null)), idempotent(token, key)),
                Created.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().title()).isEqualTo(longText.substring(0, 60));
        assertThat(created.getBody().userMessage().body()).isEqualTo(longText);
        assertThat(created.getBody().assistantMessageId()).isNotNull();
        awaitStatus(created.getBody().assistantMessageId(), "done");
        assertThat(replay.getBody().id()).isEqualTo(created.getBody().id());
        assertThat(replay.getBody().assistantMessageId()).isEqualTo(created.getBody().assistantMessageId());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_conversation WHERE owner_id = ?",
                Integer.class, owner.id())).isEqualTo(1);
        assertThat(stub.requests).hasSize(1);
    }

    @Test
    void aFirstMessageRefusedForTheQuotaOrTheSwitchLeavesNoConversation() {
        UserView owner = member("turn-refused");
        String token = login(owner.email());
        setUsage("member:" + owner.id(), today(), 100);

        ResponseEntity<Problem> overQuota = restTemplate.exchange(BASE, HttpMethod.POST, new HttpEntity<>(
                new CreateRequest(null, new FirstMessage("hello", null)), idempotent(token, UUID.randomUUID().toString())),
                Problem.class);
        setUsage("member:" + owner.id(), today(), 0);
        jdbcTemplate.update("UPDATE chat_settings SET enabled = false WHERE id = 1");
        ResponseEntity<Problem> off = restTemplate.exchange(BASE, HttpMethod.POST, new HttpEntity<>(
                new CreateRequest(null, new FirstMessage("hello", null)), idempotent(token, UUID.randomUUID().toString())),
                Problem.class);

        assertThat(overQuota.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(overQuota.getBody().code()).isEqualTo("chat-quota");
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(off.getBody().code()).isEqualTo("chat-disabled");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_conversation WHERE owner_id = ?",
                Integer.class, owner.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_idempotency WHERE owner_id = ?",
                Integer.class, owner.id())).isZero();
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void deletingTheConversationDuringATurnEndsItQuietlyWithoutARefundOrAnErrorLog(CapturedOutput output)
            throws Exception {
        UserView owner = member("turn-delete");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        CountDownLatch emitted = new CountDownLatch(1);
        stub.script(finished(), text("going"), signal(emitted), hold(new CountDownLatch(1)));
        Sent sent = sendOk(token, conversation, "then delete");
        assertThat(emitted.await(5, TimeUnit.SECONDS)).isTrue();

        ResponseEntity<Void> deleted = restTemplate.exchange(BASE + "/" + conversation, HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class);
        awaitIdle();

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rows("chat_message", "id", sent.assistantMessageId())).isZero();
        assertThat(events.updatesOf(sent.assistantMessageId())).isZero();
        assertThat(memberCount(owner.id())).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("ERROR").doesNotContain("chat.turn outcome=error");
    }

    @Test
    void sequenceNumbersComeFromTheConversationCounterAndNeverCollide() {
        UserView owner = member("turn-seq");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        seedMessage(conversation, 6, "assistant", "earlier", "done");

        Sent first = sendOk(token, conversation, "one");
        awaitStatus(first.assistantMessageId(), "done");
        awaitIdle();
        Sent second = sendOk(token, conversation, "two");
        awaitStatus(second.assistantMessageId(), "done");

        assertThat(first.userMessage().seq()).isEqualTo(7);
        assertThat(row(first.assistantMessageId())).containsEntry("seq", 8);
        assertThat(second.userMessage().seq()).isEqualTo(9);
        assertThat(row(second.assistantMessageId())).containsEntry("seq", 10);
        assertThat(jdbcTemplate.queryForObject("SELECT next_seq FROM chat_conversation WHERE id = ?",
                Integer.class, conversation)).isEqualTo(11);
    }

    @Test
    void theTitleBecomesTheFirstSixtyCharactersOnlyWhileItIsStillTheDefaultAndTheMessageIsTheFirst() {
        UserView owner = member("turn-title");
        String token = login(owner.email());
        UUID untitled = conversation(owner.id(), "Cuộc trò chuyện mới");
        UUID named = conversation(owner.id(), "Kịch bản của tôi");
        String longText = "x".repeat(100);

        Sent first = sendOk(token, untitled, longText);
        awaitStatus(first.assistantMessageId(), "done");
        awaitIdle();
        Sent second = sendOk(token, untitled, "another message");
        awaitStatus(second.assistantMessageId(), "done");
        awaitIdle();
        sendOk(token, named, "hello there");

        assertThat(titleOf(untitled)).isEqualTo("x".repeat(60));
        assertThat(titleOf(named)).isEqualTo("Kịch bản của tôi");
    }

    @Test
    void anExhaustedDayAnswers429WithTheResumeTimeLocalizedAndStoresAndChargesNothing() {
        UserView owner = member("turn-quota");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        setUsage("member:" + owner.id(), today(), 100);
        int globalBefore = globalCount();

        ResponseEntity<Problem> vi = send(token, conversation, UUID.randomUUID().toString(), "hi", Problem.class);
        var english = idempotent(token, UUID.randomUUID().toString());
        english.set("Accept-Language", "en");
        ResponseEntity<Problem> en = restTemplate.exchange(BASE + "/" + conversation + "/messages",
                HttpMethod.POST, new HttpEntity<>(new SendRequest("hi", null), english), Problem.class);

        assertThat(vi.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(vi.getBody().type()).isEqualTo(PROBLEMS.resolve("chat-quota"));
        assertThat(vi.getBody().resumeAt()).isEqualTo(today().plusDays(1).atStartOfDay(HCM).toInstant());
        assertThat(vi.getBody().detail()).contains("00:00").isNotEqualTo(en.getBody().detail());
        assertThat(en.getBody().detail())
                .isEqualTo("You've reached today's chat limit. You can chat again after 00:00.");
        assertThat(rows("chat_message", "conversation_id", conversation)).isZero();
        assertThat(memberCount(owner.id())).isEqualTo(100);
        assertThat(globalCount()).isEqualTo(globalBefore);
    }

    @Test
    void theEleventhSendInAMinuteIsRefusedUntilTheNextMinute() {
        UserView owner = member("turn-minute");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        for (int i = 0; i < 10; i++) {
            Sent sent = sendOk(token, conversation, "message " + i);
            awaitStatus(sent.assistantMessageId(), "done");
            awaitIdle();
        }

        ResponseEntity<Problem> eleventh = send(token, conversation, UUID.randomUUID().toString(), "one more",
                Problem.class);

        assertThat(eleventh.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(eleventh.getBody().code()).isEqualTo("chat-quota");
        assertThat(eleventh.getBody().resumeAt()).isAfter(Instant.now().minusSeconds(1))
                .isBefore(Instant.now().plusSeconds(61));
        assertThat(memberCount(owner.id())).isEqualTo(10);
    }

    @Test
    void aBadSendIsRefusedBeforeAnythingIsStored() {
        UserView owner = member("turn-invalid");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        String key = UUID.randomUUID().toString();

        ResponseEntity<Problem> blank = send(token, conversation, key, "   ", Problem.class);
        ResponseEntity<Problem> tooLong = send(token, conversation, key, "a".repeat(4001), Problem.class);
        ResponseEntity<Problem> noKey = send(token, conversation, null, "hello", Problem.class);
        ResponseEntity<Problem> tooManyUploads = restTemplate.exchange(BASE + "/" + conversation + "/messages",
                HttpMethod.POST, new HttpEntity<>(new SendRequest("hello",
                        java.util.stream.Stream.generate(() -> new Att(UUID.randomUUID(), "a.png")).limit(11)
                                .toList()), idempotent(token, key)),
                Problem.class);
        ResponseEntity<Problem> foreign = send(login(member("turn-invalid-other").email()), conversation, key,
                "hello", Problem.class);

        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody().errors()).containsKey("text");
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noKey.getBody().errors()).containsKey("Idempotency-Key");
        assertThat(tooManyUploads.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooManyUploads.getBody().errors()).containsKey("attachments");
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody().code()).isEqualTo("chat-conversation-not-found");
        assertThat(rows("chat_message", "conversation_id", conversation)).isZero();
        assertThat(memberCount(owner.id())).isZero();
    }

    @Test
    void attachedFilesAreKeptOnTheMemberMessageWithTheirNames() {
        UserView owner = member("turn-uploads");
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        List<Att> uploads = List.of(new Att(UUID.randomUUID(), "cup.png"), new Att(UUID.randomUUID(), "lid.png"));

        Sent sent = restTemplate.exchange(BASE + "/" + conversation + "/messages", HttpMethod.POST,
                new HttpEntity<>(new SendRequest("with pictures", uploads), idempotent(token,
                        UUID.randomUUID().toString())), Sent.class).getBody();
        awaitStatus(sent.assistantMessageId(), "done");

        assertThat(jdbcTemplate.queryForList("SELECT a->>'uploadId' FROM chat_message, "
                + "jsonb_array_elements(payload->'attachments') a WHERE id = ? ORDER BY a->>'fileName'",
                String.class, sent.userMessage().id()))
                .containsExactlyElementsOf(uploads.stream().map(a -> a.uploadId().toString()).toList());
        assertThat(jdbcTemplate.queryForList("SELECT a->>'fileName' FROM chat_message, "
                + "jsonb_array_elements(payload->'attachments') a WHERE id = ? ORDER BY a->>'fileName'",
                String.class, sent.userMessage().id())).containsExactly("cup.png", "lid.png");
    }

    /** Every thread busy and every queue slot taken: the next turn has nowhere to wait. */
    @Test
    void aTurnTheExecutorRefusesIsFailedRefundedAndPublishedAtOnce() throws Exception {
        int threads = properties.turnThreads();
        int capacity = threads + properties.turnQueue();
        int perMember = properties.quota().perMinute();
        CountDownLatch running = new CountDownLatch(threads);
        CountDownLatch gate = new CountDownLatch(1);
        for (int thread = 0; thread < threads; thread++) {
            stub.script(finished(), text("answer"), signal(running), hold(gate));
        }
        List<Sent> accepted = new ArrayList<>();
        UserView owner = null;
        String token = null;
        int sentByOwner = 0;
        Sent refused = null;
        for (int sent = 0; sent <= capacity; sent++) {
            if (sent % perMember == 0) {
                owner = member("turn-saturated");
                token = login(owner.email());
                sentByOwner = 0;
            }
            UUID conversation = conversation(owner.id(), "Thread " + sent);
            Sent result = sendOk(token, conversation, "message " + sent);
            sentByOwner++;
            if (sent < capacity) {
                accepted.add(result);
            } else {
                refused = result;
            }
            if (sent == threads - 1) {
                assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            }
        }

        assertThat(status(refused.assistantMessageId())).isEqualTo("failed");
        assertThat(errorCode(refused.assistantMessageId())).isEqualTo("busy");
        assertThat(events.updatesOf(refused.assistantMessageId())).isEqualTo(1);
        assertThat(memberCount(owner.id())).isEqualTo(sentByOwner - 1);
        gate.countDown();
        for (Sent sent : accepted) {
            awaitStatus(sent.assistantMessageId(), "done");
        }
    }

    private ResponseEntity<Sent> raced(CountDownLatch start, String token, UUID conversation, String key)
            throws InterruptedException {
        start.await();
        return send(token, conversation, key, "raced text", Sent.class);
    }

    private <T> ResponseEntity<T> retry(String token, UUID conversation, UUID assistantId, Class<T> type) {
        return restTemplate.exchange(BASE + "/" + conversation + "/messages/" + assistantId + "/retry",
                HttpMethod.POST, new HttpEntity<>(idempotent(token, UUID.randomUUID().toString())), type);
    }

    private static String stopUrl(UUID conversation, Sent sent) {
        return BASE + "/" + conversation + "/messages/" + sent.assistantMessageId() + "/stop";
    }

    private void awaitStatus(UUID messageId, String expected) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(messageId)).isEqualTo(expected));
        awaitIdle();
    }

    private void awaitIdle() {
        await().atMost(WAIT).until(runner::idle);
    }

    private String errorCode(UUID messageId) {
        return jdbcTemplate.queryForObject("SELECT payload->>'error' FROM chat_message WHERE id = ?", String.class,
                messageId);
    }

    private String titleOf(UUID conversation) {
        return jdbcTemplate.queryForObject("SELECT title FROM chat_conversation WHERE id = ?", String.class,
                conversation);
    }
}
