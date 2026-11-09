package com.flowcortex.chat.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.chat.cards.Attachment;
import com.flowcortex.chat.cards.CardStore;
import com.flowcortex.chat.conversation.ConversationStore.StoredMessage;
import com.flowcortex.chat.turn.ChatEvents;
import com.flowcortex.llm.api.LlmChatClient;
import com.flowcortex.llm.api.LlmChatListener;
import com.flowcortex.llm.api.LlmChatOutcome;
import com.flowcortex.llm.api.LlmChatRequest;
import com.flowcortex.llm.api.LlmTurn;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.ChatWorkflowView;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.api.WorkflowService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ChatToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WorkflowService workflows = mock(WorkflowService.class);
    private final WorkflowRunService runs = mock(WorkflowRunService.class);
    private final CardStore cards = mock(CardStore.class);
    private final ChatEvents events = mock(ChatEvents.class);
    private final ChatTools tools = new ChatTools(workflows, runs, cards, events, JSON);

    private final UUID owner = UUID.randomUUID();
    private final UUID conversation = UUID.randomUUID();
    private final ChatWorkflowView imageFlow = new ChatWorkflowView(UUID.randomUUID(), "Poster from a photo",
            List.of("generate_storyboard", "render_video"), "image", 120L);
    private final ChatWorkflowView textFlow = new ChatWorkflowView(UUID.randomUUID(), "Echo",
            List.of("echo"), "text", null);
    private final ChatWorkflowView videoFlow = new ChatWorkflowView(UUID.randomUUID(), "Product Video",
            List.of("generate_script", "generate_video"), "product_video", 600L);

    private ToolContext context(int attachments) {
        List<Attachment> files = IntStream.range(0, attachments)
                .mapToObj(i -> new Attachment(UUID.randomUUID(), "photo-" + i + ".png")).toList();
        return new ToolContext(owner, conversation, files);
    }

    private void catalog(ChatWorkflowView... views) {
        when(workflows.listChatCatalog(owner)).thenReturn(List.of(views));
    }

    private UUID cardWillBeStored() {
        UUID cardId = UUID.randomUUID();
        when(cards.append(any(), any(), anyString(), anyString())).thenAnswer(call -> Optional.of(
                new StoredMessage(cardId, 5, "assistant", "card", null, call.getArgument(3), "done", Instant.now())));
        return cardId;
    }

    private JsonNode propose(ToolContext context, String args) {
        ToolResult result = tools.call(context, "propose_run", args);
        assertThat(result.cardProposed()).isEqualTo(!result.json().contains("\"error\""));
        return JSON.readTree(result.json());
    }

    private void assertRefused(JsonNode result, String reason) {
        assertThat(result.path("error").asString()).isEqualTo("chat-card-invalid");
        assertThat(result.path("reason").asString()).isEqualTo(reason);
        verify(cards, never()).append(any(), any(), anyString(), anyString());
        verify(events, never()).updated(any(), any());
    }

    @Test
    void theThreeDeclarationsAreOfferedAndNoneOfThemStartsARun() {
        assertThat(tools.declarations()).extracting("name")
                .containsExactly("find_workflows", "propose_run", "run_status");
        assertThat(tools.declarations()).allSatisfy(tool -> assertThat(JSON.readTree(tool.parametersSchemaJson())
                .path("type").asString()).isEqualTo("OBJECT"));
    }

    @Test
    void findWorkflowsFiltersByTheQueryWordsAndAnEmptyQueryListsAll() {
        catalog(imageFlow, textFlow, videoFlow);

        JsonNode byName = JSON.readTree(tools.call(context(0), "find_workflows", "{\"query\":\"poster\"}").json());
        JsonNode byStep = JSON.readTree(tools.call(context(0), "find_workflows",
                "{\"query\":\"GENERATE_VIDEO\"}").json());
        JsonNode all = JSON.readTree(tools.call(context(0), "find_workflows", "{}").json());

        assertThat(byName.path("workflows")).hasSize(1);
        assertThat(byName.path("workflows").get(0).path("id").asString()).isEqualTo(imageFlow.id().toString());
        assertThat(byName.path("workflows").get(0).path("inputKind").asString()).isEqualTo("image");
        assertThat(byName.path("workflows").get(0).has("estimatedCredits")).isFalse();
        assertThat(byStep.path("workflows")).hasSize(1);
        assertThat(all.path("workflows")).hasSize(3);
    }

    @Test
    void findWorkflowsReturnsAtMostTwentyWorkflows() {
        List<ChatWorkflowView> many = IntStream.range(0, 30).mapToObj(i -> new ChatWorkflowView(UUID.randomUUID(),
                "Flow " + i, List.of("echo"), "text", null)).toList();
        when(workflows.listChatCatalog(owner)).thenReturn(many);

        JsonNode result = JSON.readTree(tools.call(context(0), "find_workflows", "{\"query\":\"\"}").json());

        assertThat(result.path("workflows")).hasSize(20);
    }

    @Test
    void proposeRunRefusesAWorkflowTheMemberCannotSee() {
        catalog(textFlow);

        assertRefused(propose(context(1), "{\"workflowId\":\"" + imageFlow.id() + "\"}"), "workflow_not_visible");
        assertRefused(propose(context(1), "{\"workflowId\":\"not-a-uuid\"}"), "workflow_not_visible");
        assertRefused(propose(context(1), "{}"), "workflow_not_visible");
    }

    @Test
    void proposeRunRefusesAnImageWorkflowWithNoImageOrMoreThanTen() {
        catalog(imageFlow);
        String args = "{\"workflowId\":\"" + imageFlow.id() + "\"}";

        assertRefused(propose(context(0), args), "attachments");
        assertRefused(propose(context(11), args), "attachments");
    }

    @Test
    void proposeRunRefusesAProductVideoWorkflowWithoutExactlyOneImage() {
        catalog(videoFlow);
        String args = "{\"workflowId\":\"" + videoFlow.id() + "\",\"productVideo\":{\"productName\":\"Lamp\","
                + "\"description\":\"A lamp\"}}";

        assertRefused(propose(context(0), args), "attachments");
        assertRefused(propose(context(2), args), "attachments");
    }

    @Test
    void proposeRunRefusesATextWorkflowWithABlankOrOverlongInput() {
        catalog(textFlow);
        String id = "\"workflowId\":\"" + textFlow.id() + "\"";

        assertRefused(propose(context(0), "{" + id + "}"), "input");
        assertRefused(propose(context(0), "{" + id + ",\"inputText\":\"   \"}"), "input");
        assertRefused(propose(context(0), "{" + id + ",\"inputText\":\"" + "x".repeat(10_001) + "\"}"), "input");
    }

    @Test
    void proposeRunRefusesAProductVideoCardWithABlankOrOverlongField() {
        catalog(videoFlow);
        String id = "\"workflowId\":\"" + videoFlow.id() + "\"";

        JsonNode blank = propose(context(1), "{" + id + ",\"productVideo\":{\"productName\":\" \","
                + "\"description\":\"A lamp\"}}");
        JsonNode overlong = propose(context(1), "{" + id + ",\"productVideo\":{\"productName\":\"Lamp\","
                + "\"description\":\"" + "d".repeat(4001) + "\",\"price\":\"" + "9".repeat(201) + "\"}}");
        JsonNode missing = propose(context(1), "{" + id + "}");

        assertThat(blank.path("error").asString()).isEqualTo("chat-card-invalid");
        assertThat(blank.path("reason").asString()).isEqualTo("fields");
        assertThat(blank.path("fields")).extracting(JsonNode::asString).containsExactly("productName");
        assertThat(overlong.path("fields")).extracting(JsonNode::asString)
                .containsExactlyInAnyOrder("description", "price");
        assertThat(missing.path("fields")).extracting(JsonNode::asString)
                .containsExactlyInAnyOrder("productName", "description");
        verify(cards, never()).append(any(), any(), anyString(), anyString());
        verify(events, never()).updated(any(), any());
    }

    @Test
    void aValidImageProposalStoresAPendingCardAndAnnouncesIt() {
        catalog(imageFlow);
        UUID cardId = cardWillBeStored();
        ToolContext context = context(2);

        JsonNode result = propose(context, "{\"workflowId\":\"" + imageFlow.id() + "\"}");

        assertThat(result.path("proposed").asBoolean()).isTrue();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(cards).append(any(), any(), anyString(), payload.capture());
        JsonNode card = JSON.readTree(payload.getValue());
        assertThat(card.path("type").asString()).isEqualTo("run");
        assertThat(card.path("workflowId").asString()).isEqualTo(imageFlow.id().toString());
        assertThat(card.path("workflowName").asString()).isEqualTo("Poster from a photo");
        assertThat(card.path("state").asString()).isEqualTo("pending");
        assertThat(card.path("runId").isNull()).isTrue();
        assertThat(card.path("estimatedCredits").asLong()).isEqualTo(120);
        assertThat(card.path("uploads")).hasSize(2);
        assertThat(card.path("uploads").get(0).path("uploadId").asString())
                .isEqualTo(context.attachments().get(0).uploadId().toString());
        assertThat(card.path("uploads").get(0).path("fileName").asString()).isEqualTo("photo-0.png");
        verify(cards).append(owner, conversation, "card", payload.getValue());
        verify(events).updated(conversation, cardId);
    }

    @Test
    void aValidTextProposalKeepsTheTrimmedInputAndNoUploads() {
        catalog(textFlow);
        cardWillBeStored();

        propose(context(3), "{\"workflowId\":\"" + textFlow.id() + "\",\"inputText\":\"  hello  \"}");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(cards).append(any(), any(), anyString(), payload.capture());
        JsonNode card = JSON.readTree(payload.getValue());
        assertThat(card.path("input").asString()).isEqualTo("hello");
        assertThat(card.path("uploads")).isEmpty();
        assertThat(card.path("estimatedCredits").isNull()).isTrue();
    }

    @Test
    void aValidProductVideoProposalCarriesTheTrimmedProductFields() {
        catalog(videoFlow);
        cardWillBeStored();

        propose(context(1), "{\"workflowId\":\"" + videoFlow.id() + "\",\"productVideo\":{\"productName\":\" Lamp \","
                + "\"description\":\"A desk lamp\",\"cta\":\"Buy now\"}}");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(cards).append(any(), any(), anyString(), payload.capture());
        JsonNode card = JSON.readTree(payload.getValue());
        assertThat(card.path("type").asString()).isEqualTo("product_video");
        assertThat(card.path("productVideo").path("productName").asString()).isEqualTo("Lamp");
        assertThat(card.path("productVideo").path("cta").asString()).isEqualTo("Buy now");
        assertThat(card.path("productVideo").path("price").isNull()).isTrue();
        assertThat(card.path("uploads")).hasSize(1);
        assertThat(card.path("input").isNull()).isTrue();
    }

    @Test
    void runStatusReportsAnOwnedRunAndRefusesAForeignOne() {
        UUID runId = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        RunStepView step = new RunStepView(UUID.randomUUID(), 1, "echo", "running", null, null, null, null, null, 40,
                1, null, 1, null, List.of(), "inline");
        when(runs.getRun(owner, runId)).thenReturn(new RunView(runId, textFlow.id(), owner, "running", "hi", null,
                Instant.now(), null, List.of(step)));
        when(runs.getRun(owner, foreign)).thenThrow(new NotFoundException(new LocalizedMessage("run.notFound",
                foreign)));

        JsonNode own = JSON.readTree(tools.call(context(0), "run_status", "{\"runId\":\"" + runId + "\"}").json());
        JsonNode other = JSON.readTree(tools.call(context(0), "run_status", "{\"runId\":\"" + foreign + "\"}").json());
        JsonNode garbage = JSON.readTree(tools.call(context(0), "run_status", "{\"runId\":\"x\"}").json());

        assertThat(own.path("status").asString()).isEqualTo("running");
        assertThat(own.path("steps").get(0).path("order").asInt()).isEqualTo(1);
        assertThat(own.path("steps").get(0).path("type").asString()).isEqualTo("echo");
        assertThat(own.path("steps").get(0).path("progressPercent").asInt()).isEqualTo(40);
        assertThat(other.path("error").asString()).isEqualTo("not found");
        assertThat(other.has("status")).isFalse();
        assertThat(garbage.path("error").asString()).isEqualTo("not found");
    }

    @Test
    void anUnknownToolAndBrokenArgumentsAreToolErrorsNotExceptions() {
        assertThat(JSON.readTree(tools.call(context(0), "start_run", "{}").json()).path("error").asString())
                .isEqualTo("unknown-tool");
        assertThat(JSON.readTree(tools.call(context(0), "find_workflows", "{not json").json()).path("error")
                .asString()).isEqualTo("invalid-arguments");
    }

    /** Asks for a tool on every round it is called. */
    private static final class AlwaysCalling implements LlmChatClient {

        final List<LlmChatRequest> requests = new ArrayList<>();

        @Override
        public LlmChatOutcome stream(LlmChatRequest request, LlmChatListener listener) {
            requests.add(request);
            listener.onText("round " + requests.size() + ". ");
            return new LlmChatOutcome.Finished("m", 10, 5, List.of(
                    new LlmChatOutcome.ToolCall("find_workflows", "{}", "sig-" + requests.size())));
        }
    }

    private static class Collector implements LlmChatListener {

        final StringBuilder text = new StringBuilder();
        int rounds;

        @Override
        public void onText(String chunk) {
            text.append(chunk);
        }

        @Override
        public void onToolCall(String name, String argsJson, String providerState) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    }

    private LlmChatRequest firstRequest() {
        return new LlmChatRequest("m", "system", List.of(new LlmTurn.User("hi")), tools.declarations(), 0.7, 256,
                "en");
    }

    @Test
    void aFifthToolRoundEndsTheTurnWithTheTextSoFar() {
        catalog(textFlow);
        AlwaysCalling llm = new AlwaysCalling();
        Collector listener = new Collector();

        ToolRounds.Result result = ToolRounds.run(llm, firstRequest(), tools, context(0), listener, 4,
                () -> listener.rounds++);

        assertThat(llm.requests).hasSize(5);
        assertThat(listener.rounds).isEqualTo(4);
        assertThat(result.limitReached()).isTrue();
        assertThat(result.outcome()).isInstanceOfSatisfying(LlmChatOutcome.Finished.class, finished -> {
            assertThat(finished.toolCalls()).isEmpty();
            assertThat(finished.inputTokens()).isEqualTo(50);
            assertThat(finished.outputTokens()).isEqualTo(25);
        });
        assertThat(listener.text.toString()).isEqualTo("round 1. round 2. round 3. round 4. round 5. ");
        verify(workflows, times(4)).listChatCatalog(owner);
    }

    @Test
    void eachRoundReplaysTheCallWithItsProviderStateAndItsResult() {
        catalog(textFlow);
        AlwaysCalling llm = new AlwaysCalling();

        ToolRounds.run(llm, firstRequest(), tools, context(0), new Collector(), 2, () -> { });

        List<LlmTurn> third = llm.requests.get(2).turns();
        assertThat(third).hasSize(5);
        assertThat(third.get(1)).isEqualTo(new LlmTurn.FunctionCall("find_workflows", "{}", "sig-1"));
        assertThat(third.get(2)).isInstanceOf(LlmTurn.FunctionResult.class);
        assertThat(((LlmTurn.FunctionResult) third.get(2)).name()).isEqualTo("find_workflows");
        assertThat(third.get(3)).isEqualTo(new LlmTurn.FunctionCall("find_workflows", "{}", "sig-2"));
        assertThat(llm.requests.get(2).tools()).isEqualTo(llm.requests.get(0).tools());
    }

    @Test
    void aTurnWithoutToolCallsIsOneRequest() {
        LlmChatClient plain = (request, listener) -> {
            listener.onText("hello");
            return new LlmChatOutcome.Finished("m", 3, 2, List.of());
        };

        ToolRounds.Result result = ToolRounds.run(plain, firstRequest(), tools, context(0), new Collector(), 4,
                () -> { });

        assertThat(result.limitReached()).isFalse();
        assertThat(result.cardProposed()).isFalse();
        assertThat(result.outcome()).isInstanceOf(LlmChatOutcome.Finished.class);
    }

    @Test
    void aCancelledListenerStopsBeforeTheToolsRunAndBeforeTheNextRound() {
        catalog(textFlow);
        cardWillBeStored();
        List<LlmChatRequest> seen = new ArrayList<>();
        Collector cancelling = new Collector() {
            @Override
            public boolean cancelled() {
                return !seen.isEmpty();
            }
        };
        LlmChatClient client = (request, listener) -> {
            seen.add(request);
            return new LlmChatOutcome.Finished("m", 1, 1, List.of(new LlmChatOutcome.ToolCall("propose_run",
                    "{\"workflowId\":\"" + textFlow.id() + "\",\"inputText\":\"go\"}", null)));
        };

        ToolRounds.Result result = ToolRounds.run(client, firstRequest(), tools, context(0), cancelling, 4,
                () -> { });

        assertThat(seen).hasSize(1);
        assertThat(result.outcome()).isInstanceOf(LlmChatOutcome.Cancelled.class);
        verify(cards, never()).append(any(), any(), anyString(), anyString());
    }

    @Test
    void aProposedCardIsReportedByTheRounds() {
        catalog(textFlow);
        cardWillBeStored();
        int[] calls = {0};
        LlmChatClient client = (request, listener) -> calls[0]++ == 0
                ? new LlmChatOutcome.Finished("m", 1, 1, List.of(new LlmChatOutcome.ToolCall("propose_run",
                        "{\"workflowId\":\"" + textFlow.id() + "\",\"inputText\":\"go\"}", null)))
                : new LlmChatOutcome.Finished("m", 1, 1, List.of());

        ToolRounds.Result result = ToolRounds.run(client, firstRequest(), tools, context(0), new Collector(), 4,
                () -> { });

        assertThat(result.cardProposed()).isTrue();
        assertThat(result.limitReached()).isFalse();
    }
}
