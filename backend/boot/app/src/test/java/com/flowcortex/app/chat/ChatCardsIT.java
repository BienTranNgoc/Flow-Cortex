package com.flowcortex.app.chat;

import static com.flowcortex.app.chat.ChatTestConfig.StubChatClient.finished;
import static com.flowcortex.app.chat.ChatTestConfig.StubChatClient.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.llm.api.LlmChatOutcome;
import com.flowcortex.llm.api.LlmTurn;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowView;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cards over HTTP with a scripted provider: the model proposes a run through the tool loop, and the member's
 * confirm is the only thing that starts one. Dedicated context: credits and Product Video are on, and
 * {@code echo} costs 10, {@code probe} 40.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.chat.enabled=true",
        "flowcortex.product-video.enabled=true",
        "flowcortex.credits.enabled=true",
        "flowcortex.credits.step-costs.echo=10",
        "flowcortex.credits.step-costs.probe=40",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
@Import(ChatTestConfig.class)
class ChatCardsIT extends ChatItSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(8);

    @Autowired
    private WorkflowService workflowService;

    record Pv(String productName, String description, String cta, String price, String promotion) {
    }

    record Confirm(Boolean acknowledgeCost, Pv productVideo, List<Att> attachments) {
    }

    record CardView(UUID id, int seq, String role, String kind, Map<String, Object> payload, String status) {
    }

    record Confirmed(UUID runId, CardView card) {
    }

    record Refusal(int status, String code, Map<String, String> errors, Long balance, Long required,
            Long shortfall) {
    }

    record UploadRequest(String fileName, long size) {
    }

    record UploadGrant(UUID uploadId) {
    }

    private WorkflowView imageWorkflow;
    private WorkflowView textWorkflow;

    private void workflows() {
        if (imageWorkflow == null) {
            imageWorkflow = workflowService.createWorkflow(admin.id(), "chat-image-" + UUID.randomUUID(), true,
                    List.of(new NewWorkflowStep("scan", "probe", null)));
            textWorkflow = workflowService.createWorkflow(admin.id(), "chat-text-" + UUID.randomUUID(), true,
                    List.of(new NewWorkflowStep("say", "echo", null)));
        }
    }

    private UserView fundedMember(String prefix, long balance) {
        UserView member = member(prefix);
        await().atMost(WAIT).until(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class, member.id()) == 1);
        jdbcTemplate.update("UPDATE credit_wallet SET balance = ? WHERE user_id = ?", balance, member.id());
        return member;
    }

    private UUID upload(UserView owner, String token, String fileName) {
        ResponseEntity<UploadGrant> grant = restTemplate.exchange("/api/v1/uploads", HttpMethod.POST,
                new HttpEntity<>(new UploadRequest(fileName, 16), bearer(token)), UploadGrant.class);
        assertThat(grant.getStatusCode().value()).isEqualTo(201);
        UUID uploadId = grant.getBody().uploadId();
        try (S3Client store = objectStore()) {
            store.putObject(request -> request.bucket(UPLOAD_BUCKET).key(owner.id() + "/" + uploadId)
                    .contentType("image/png"),
                    RequestBody.fromBytes("sixteen bytes!!!".getBytes(StandardCharsets.UTF_8)));
        }
        return uploadId;
    }

    private void expire(UserView owner, UUID uploadId) {
        try (S3Client store = objectStore()) {
            store.deleteObject(request -> request.bucket(UPLOAD_BUCKET).key(owner.id() + "/" + uploadId));
        }
    }

    private UUID card(UUID conversation, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        int seq = jdbcTemplate.queryForObject("SELECT next_seq FROM chat_conversation WHERE id = ?", Integer.class,
                conversation);
        jdbcTemplate.update("INSERT INTO chat_message (id, conversation_id, seq, role, kind, payload, status) "
                + "VALUES (?, ?, ?, 'assistant', 'card', ?::jsonb, 'done')", id, conversation, seq,
                JSON.writeValueAsString(payload));
        jdbcTemplate.update("UPDATE chat_conversation SET next_seq = ? WHERE id = ?", seq + 1, conversation);
        return id;
    }

    private Map<String, Object> runCard(UUID workflowId, String name, List<Att> uploads, String input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "run");
        payload.put("workflowId", workflowId);
        payload.put("workflowName", name);
        payload.put("uploads", uploads);
        payload.put("input", input);
        payload.put("productVideo", null);
        payload.put("estimatedCredits", 40);
        payload.put("state", "pending");
        payload.put("runId", null);
        return payload;
    }

    private Map<String, Object> videoCard(List<Att> uploads, Pv pv) {
        Map<String, Object> payload = runCard(ProductVideoWorkflow.ID, "Product Video", uploads, null);
        payload.put("type", "product_video");
        payload.put("productVideo", pv);
        return payload;
    }

    private <T> ResponseEntity<T> confirm(String token, UUID conversation, UUID card, Confirm body, Class<T> type) {
        HttpHeaders headers = bearer(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        return restTemplate.exchange(BASE + "/" + conversation + "/cards/" + card + "/confirm", HttpMethod.POST,
                new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<Confirmed> confirmOk(String token, UUID conversation, UUID card, Confirm body) {
        ResponseEntity<Confirmed> response = confirm(token, conversation, card, body, Confirmed.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response;
    }

    private int runsOf(UserView owner) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_run WHERE owner_id = ?", Integer.class,
                owner.id());
    }

    private int runMessages(UUID conversation) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM chat_message WHERE conversation_id = ? "
                + "AND kind = 'run'", Integer.class, conversation);
    }

    private String cardState(UUID card) {
        return jdbcTemplate.queryForObject("SELECT payload->>'state' FROM chat_message WHERE id = ?", String.class,
                card);
    }

    @Test
    void confirmStartsOneRunWithTheCardInputsAndAppendsARunMessage() {
        workflows();
        UserView owner = fundedMember("card-start", 1000);
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        UUID cup = upload(owner, token, "cup.png");
        UUID image = card(conversation, runCard(imageWorkflow.id(), imageWorkflow.name(),
                List.of(new Att(cup, "cup.png")), null));
        UUID text = card(conversation, runCard(textWorkflow.id(), textWorkflow.name(), List.of(), "say hi"));

        assertThat(runsOf(owner)).isZero();
        Confirmed imageRun = confirmOk(token, conversation, image, null).getBody();
        Confirmed textRun = confirmOk(token, conversation, text, new Confirm(null, null, null)).getBody();

        assertThat(imageRun.runId()).isNotNull().isNotEqualTo(textRun.runId());
        assertThat(imageRun.card().id()).isEqualTo(image);
        assertThat(imageRun.card().payload()).containsEntry("state", "started")
                .containsEntry("runId", imageRun.runId().toString());
        assertThat(runsOf(owner)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap("SELECT workflow_id, input, request_key FROM workflow_run WHERE id = ?",
                imageRun.runId())).containsEntry("workflow_id", imageWorkflow.id())
                .containsEntry("input", "cup.png").containsEntry("request_key", "chat-card:" + image);
        assertThat(jdbcTemplate.queryForObject("SELECT input FROM workflow_run WHERE id = ?", String.class,
                textRun.runId())).isEqualTo("say hi");
        assertThat(runMessages(conversation)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT payload->>'runId' FROM chat_message "
                + "WHERE conversation_id = ? AND kind = 'run' AND seq = ?", String.class, conversation,
                jdbcTemplate.queryForObject("SELECT seq FROM chat_message WHERE id = ?", Integer.class, image) + 2))
                .isEqualTo(imageRun.runId().toString());
        assertThat(events.updatesOf(image)).isEqualTo(1);
    }

    @Test
    void aSecondConfirmReturnsTheSameRunAndAppendsNothing() {
        workflows();
        UserView owner = fundedMember("card-twice", 1000);
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        UUID card = card(conversation, runCard(textWorkflow.id(), textWorkflow.name(), List.of(), "say hi"));

        Confirmed first = confirmOk(token, conversation, card, null).getBody();
        Confirmed second = confirmOk(token, conversation, card, null).getBody();

        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.card().payload()).containsEntry("state", "started");
        assertThat(runsOf(owner)).isEqualTo(1);
        assertThat(runMessages(conversation)).isEqualTo(1);
    }

    @Test
    void twoConfirmsAtOnceStartOneRunAndBothAnswerItsId() throws Exception {
        workflows();
        UserView owner = fundedMember("card-race", 1000);
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        UUID cup = upload(owner, token, "cup.png");
        UUID card = card(conversation, runCard(imageWorkflow.id(), imageWorkflow.name(),
                List.of(new Att(cup, "cup.png")), null));
        CountDownLatch go = new CountDownLatch(1);
        Callable<ResponseEntity<Confirmed>> click = () -> {
            go.await();
            return confirm(token, conversation, card, null, Confirmed.class);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Confirmed>> a = pool.submit(click);
            Future<ResponseEntity<Confirmed>> b = pool.submit(click);
            go.countDown();
            ResponseEntity<Confirmed> first = a.get(9, TimeUnit.SECONDS);
            ResponseEntity<Confirmed> second = b.get(9, TimeUnit.SECONDS);
            assertThat(first.getStatusCode().value()).isEqualTo(200);
            assertThat(second.getStatusCode().value()).isEqualTo(200);
            assertThat(second.getBody().runId()).isEqualTo(first.getBody().runId());
        } finally {
            pool.shutdownNow();
        }

        assertThat(runsOf(owner)).isEqualTo(1);
        assertThat(runMessages(conversation)).isEqualTo(1);
        assertThat(cardState(card)).isEqualTo("started");
    }

    @Test
    void anExpiredImageIsRefusedWithImagesExpiredAndNewAttachmentsStartTheRun() {
        workflows();
        UserView owner = fundedMember("card-expired", 1000);
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        UUID cup = upload(owner, token, "cup.png");
        UUID card = card(conversation, runCard(imageWorkflow.id(), imageWorkflow.name(),
                List.of(new Att(cup, "cup.png")), null));
        expire(owner, cup);

        ResponseEntity<Refusal> expired = confirm(token, conversation, card, null, Refusal.class);

        assertThat(expired.getStatusCode().value()).isEqualTo(409);
        assertThat(expired.getBody().code()).isEqualTo("chat-card-images-expired");
        assertThat(cardState(card)).isEqualTo("images-expired");
        assertThat(runsOf(owner)).isZero();
        assertThat(runMessages(conversation)).isZero();

        UUID fresh = upload(owner, token, "new-cup.png");
        Confirmed started = confirmOk(token, conversation, card,
                new Confirm(null, null, List.of(new Att(fresh, "new-cup.png")))).getBody();

        assertThat(cardState(card)).isEqualTo("started");
        assertThat(jdbcTemplate.queryForObject("SELECT payload->'uploads'->0->>'fileName' FROM chat_message "
                + "WHERE id = ?", String.class, card)).isEqualTo("new-cup.png");
        assertThat(jdbcTemplate.queryForObject("SELECT input FROM workflow_run WHERE id = ?", String.class,
                started.runId())).isEqualTo("new-cup.png");
        assertThat(runsOf(owner)).isEqualTo(1);
    }

    @Test
    void anotherMembersUploadIsTreatedLikeAnExpiredOne() {
        workflows();
        UserView owner = fundedMember("card-foreign-upload", 1000);
        UserView other = member("card-upload-owner");
        UUID conversation = conversation(owner.id(), "Thread");
        UUID theirs = upload(other, login(other.email()), "theirs.png");
        UUID card = card(conversation, runCard(imageWorkflow.id(), imageWorkflow.name(),
                List.of(new Att(theirs, "theirs.png")), null));

        ResponseEntity<Refusal> refused = confirm(login(owner.email()), conversation, card, null, Refusal.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody().code()).isEqualTo("chat-card-images-expired");
        assertThat(runsOf(owner)).isZero();
        assertThat(runsOf(other)).isZero();
    }

    @Test
    void newAttachmentsAreCheckedLikeTheComposerAndCountedPerCardKind() {
        workflows();
        UserView owner = fundedMember("card-attachments", 1000);
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        UUID cup = upload(owner, token, "cup.png");
        UUID video = card(conversation, videoCard(List.of(new Att(cup, "cup.png")),
                new Pv("Lamp", "A desk lamp", null, null, null)));
        UUID image = card(conversation, runCard(imageWorkflow.id(), imageWorkflow.name(),
                List.of(new Att(cup, "cup.png")), null));
        Att one = new Att(UUID.randomUUID(), "one.png");
        Att two = new Att(UUID.randomUUID(), "two.png");

        ResponseEntity<Refusal> twoForVideo = confirm(token, conversation, video,
                new Confirm(null, null, List.of(one, two)), Refusal.class);
        ResponseEntity<Refusal> noneForImage = confirm(token, conversation, image,
                new Confirm(null, null, List.of()), Refusal.class);
        ResponseEntity<Refusal> duplicated = confirm(token, conversation, image,
                new Confirm(null, null, List.of(one, one)), Refusal.class);
        ResponseEntity<Refusal> nameless = confirm(token, conversation, image,
                new Confirm(null, null, List.of(new Att(one.uploadId(), " "))), Refusal.class);

        for (ResponseEntity<Refusal> refusal : List.of(twoForVideo, noneForImage, duplicated, nameless)) {
            assertThat(refusal.getStatusCode().value()).isEqualTo(400);
            assertThat(refusal.getBody().code()).isEqualTo("chat-card-invalid");
            assertThat(refusal.getBody().errors()).containsKey("attachments");
        }
        assertThat(runsOf(owner)).isZero();
        assertThat(cardState(video)).isEqualTo("pending");
    }

    @Test
    void aRefusalForCreditsPassesThroughStartsNothingAndAnAcknowledgedRetryStartsTheRun() {
        workflows();
        UserView owner = fundedMember("card-credits", 5);
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        UUID cup = upload(owner, token, "cup.png");
        UUID card = card(conversation, runCard(imageWorkflow.id(), imageWorkflow.name(),
                List.of(new Att(cup, "cup.png")), null));

        ResponseEntity<Refusal> refused = confirm(token, conversation, card, null, Refusal.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(402);
        assertThat(refused.getBody().code()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(refused.getBody().balance()).isEqualTo(5);
        assertThat(refused.getBody().required()).isEqualTo(40);
        assertThat(refused.getBody().shortfall()).isEqualTo(35);
        assertThat(runsOf(owner)).isZero();
        assertThat(runMessages(conversation)).isZero();
        assertThat(cardState(card)).isEqualTo("pending");

        Confirmed started = confirmOk(token, conversation, card, new Confirm(true, null, null)).getBody();

        assertThat(started.runId()).isNotNull();
        assertThat(runsOf(owner)).isEqualTo(1);
        assertThat(cardState(card)).isEqualTo("started");
    }

    @Test
    void aProductVideoCardWithEditedFieldsStartsThroughProductVideoRuns() {
        workflows();
        UserView owner = fundedMember("card-video", 100_000);
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        UUID cup = upload(owner, token, "cup.png");
        UUID card = card(conversation, videoCard(List.of(new Att(cup, "cup.png")),
                new Pv("Binh", "Mo ta", null, null, null)));

        Confirmed started = confirmOk(token, conversation, card, new Confirm(null,
                new Pv("Bình giữ nhiệt", "Giữ đồ uống nóng 12 giờ", "Mua ngay", "199.000đ", null), null)).getBody();

        assertThat(jdbcTemplate.queryForObject("SELECT workflow_id FROM workflow_run WHERE id = ?", UUID.class,
                started.runId())).isEqualTo(ProductVideoWorkflow.ID);
        assertThat(jdbcTemplate.queryForMap("SELECT product_name, description, cta, price, promotion, aspect_ratio "
                + "FROM product_video_input WHERE run_id = ?", started.runId()))
                .containsEntry("product_name", "Bình giữ nhiệt")
                .containsEntry("description", "Giữ đồ uống nóng 12 giờ").containsEntry("cta", "Mua ngay")
                .containsEntry("price", "199.000đ").containsEntry("promotion", null)
                .containsEntry("aspect_ratio", "9:16");
        assertThat(jdbcTemplate.queryForObject("SELECT request_key FROM workflow_run WHERE id = ?", String.class,
                started.runId())).isEqualTo("chat-card:" + card);
        assertThat(jdbcTemplate.queryForObject("SELECT payload->'productVideo'->>'productName' FROM chat_message "
                + "WHERE id = ?", String.class, card)).isEqualTo("Bình giữ nhiệt");
        assertThat(runMessages(conversation)).isEqualTo(1);
    }

    @Test
    void invalidProductEditsAre400WithLocalizedFieldErrorsAndStartNothing() {
        workflows();
        UserView owner = fundedMember("card-invalid", 100_000);
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        UUID cup = upload(owner, token, "cup.png");
        UUID card = card(conversation, videoCard(List.of(new Att(cup, "cup.png")),
                new Pv("Binh", "Mo ta", null, null, null)));

        ResponseEntity<Refusal> refused = confirm(token, conversation, card, new Confirm(null,
                new Pv(" ", "d".repeat(4001), "  ", null, null), null), Refusal.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(refused.getBody().code()).isEqualTo("chat-card-invalid");
        assertThat(refused.getBody().errors()).containsOnlyKeys("productName", "description", "cta");
        assertThat(refused.getBody().errors().get("productName")).isEqualTo("Tên sản phẩm cần từ 1 đến 200 ký tự");
        assertThat(refused.getBody().errors().values()).noneMatch(message -> message.contains("must not"));
        assertThat(runsOf(owner)).isZero();
        assertThat(cardState(card)).isEqualTo("pending");
        assertThat(jdbcTemplate.queryForObject("SELECT payload->'productVideo'->>'productName' FROM chat_message "
                + "WHERE id = ?", String.class, card)).isEqualTo("Binh");
    }

    @Test
    void aForeignOrMissingCardIsA404AndStartsNothing() {
        workflows();
        UserView owner = fundedMember("card-owner", 1000);
        UserView other = fundedMember("card-stranger", 1000);
        UUID conversation = conversation(owner.id(), "Thread");
        UUID card = card(conversation, runCard(textWorkflow.id(), textWorkflow.name(), List.of(), "say hi"));
        UUID plain = seedMessage(conversation, 9, "assistant", "just text", "done");
        String ownerToken = login(owner.email());

        ResponseEntity<Refusal> foreign = confirm(login(other.email()), conversation, card, null, Refusal.class);
        ResponseEntity<Refusal> missing = confirm(ownerToken, conversation, UUID.randomUUID(), null, Refusal.class);
        ResponseEntity<Refusal> notACard = confirm(ownerToken, conversation, plain, null, Refusal.class);
        ResponseEntity<Refusal> unknownConversation = confirm(ownerToken, UUID.randomUUID(), card, null,
                Refusal.class);

        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
        assertThat(foreign.getBody().code()).isEqualTo("chat-conversation-not-found");
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody().code()).isEqualTo("chat-message-not-found");
        assertThat(notACard.getBody().code()).isEqualTo("chat-message-not-found");
        assertThat(unknownConversation.getBody().code()).isEqualTo("chat-conversation-not-found");
        assertThat(runsOf(owner) + runsOf(other)).isZero();
        assertThat(cardState(card)).isEqualTo("pending");
    }

    @Test
    void aModelProposalBecomesAPendingCardAfterItsTextAndNoRunExistsUntilConfirm() {
        workflows();
        UserView owner = fundedMember("card-tool", 1000);
        String token = login(owner.email());
        UUID conversation = conversation(owner.id(), "Thread");
        Att cup = new Att(UUID.randomUUID(), "cup.png");
        stub.script(new LlmChatOutcome.Finished("stub", 4, 2, List.of(new LlmChatOutcome.ToolCall("propose_run",
                "{\"workflowId\":\"" + imageWorkflow.id() + "\"}", "sig-1"))), text("Let me set that up."));
        stub.script(finished(), text("Check the card."));

        ResponseEntity<Sent> sent = restTemplate.exchange(BASE + "/" + conversation + "/messages", HttpMethod.POST,
                new HttpEntity<>(new SendRequest("make a clip", List.of(cup)),
                        idempotent(token, UUID.randomUUID().toString())), Sent.class);
        UUID answer = sent.getBody().assistantMessageId();
        await().atMost(WAIT).untilAsserted(() -> assertThat(status(answer)).isEqualTo("done"));
        await().atMost(WAIT).until(runner::idle);

        assertThat(jdbcTemplate.queryForObject("SELECT body FROM chat_message WHERE id = ?", String.class, answer))
                .isEqualTo("Let me set that up.\n\nCheck the card.");
        Map<String, Object> cardRow = jdbcTemplate.queryForMap("SELECT id, seq, role, status, payload::text AS p "
                + "FROM chat_message WHERE conversation_id = ? AND kind = 'card'", conversation);
        assertThat(cardRow.get("seq")).isEqualTo(3);
        assertThat(cardRow.get("role")).isEqualTo("assistant");
        assertThat(cardRow.get("p").toString()).contains("\"state\": \"pending\"", "cup.png",
                imageWorkflow.id().toString()).contains(cup.uploadId().toString());
        assertThat(events.updatesOf((UUID) cardRow.get("id"))).isEqualTo(1);
        assertThat(stub.requests).hasSize(2);
        assertThat(stub.requests.get(1).turns()).anySatisfy(turn -> assertThat(turn)
                .isEqualTo(new LlmTurn.FunctionCall("propose_run",
                        "{\"workflowId\":\"" + imageWorkflow.id() + "\"}", "sig-1")));
        assertThat(stub.requests.get(1).turns()).anyMatch(LlmTurn.FunctionResult.class::isInstance);
        assertThat(runsOf(owner)).isZero();
        assertThat(((LlmTurn.User) stub.requests.get(0).turns().getLast()).text()).contains("cup.png");
    }
}
