package com.flowcortex.chat.tools;

import com.flowcortex.chat.cards.Attachment;
import com.flowcortex.chat.cards.CardFields;
import com.flowcortex.chat.cards.CardStore;
import com.flowcortex.chat.conversation.ConversationStore.StoredMessage;
import com.flowcortex.chat.turn.ChatEvents;
import com.flowcortex.llm.api.LlmTool;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.ChatWorkflowView;
import com.flowcortex.workflow.api.ProductVideoDraft;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.api.WorkflowService;
import jakarta.validation.ConstraintViolationException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The functions the model may call. Arguments are untrusted model output: every id is parsed, every value is
 * validated here, and every lookup is scoped to the member. {@code propose_run} only ever stores a pending
 * card; no tool starts a run.
 */
@Component("chatTools")
public class ChatTools {

    static final String FIND_WORKFLOWS = "find_workflows";
    static final String PROPOSE_RUN = "propose_run";
    static final String RUN_STATUS = "run_status";

    static final int MAX_FOUND = 20;
    static final int MAX_IMAGES = 10;
    static final int MAX_TEXT_INPUT_CHARS = 10_000;
    static final String ASPECT_RATIO = "9:16";

    private static final Logger LOG = LoggerFactory.getLogger(ChatTools.class);

    private static final String SCHEMAS_FIND = "{\"type\":\"OBJECT\",\"properties\":{\"query\":{\"type\":\"STRING\","
            + "\"description\":\"Words describing what the member wants to make; empty lists every workflow.\"}}}";
    private static final String SCHEMAS_PROPOSE = "{\"type\":\"OBJECT\",\"properties\":{"
            + "\"workflowId\":{\"type\":\"STRING\",\"description\":\"The id of a workflow from find_workflows.\"},"
            + "\"inputText\":{\"type\":\"STRING\",\"description\":\"The text input, for a workflow whose "
            + "inputKind is text.\"},"
            + "\"productVideo\":{\"type\":\"OBJECT\",\"description\":\"The product, for a workflow whose "
            + "inputKind is product_video.\",\"properties\":{"
            + "\"productName\":{\"type\":\"STRING\"},\"description\":{\"type\":\"STRING\"},"
            + "\"cta\":{\"type\":\"STRING\"},\"price\":{\"type\":\"STRING\"},\"promotion\":{\"type\":\"STRING\"}}}},"
            + "\"required\":[\"workflowId\"]}";
    private static final String SCHEMAS_STATUS = "{\"type\":\"OBJECT\",\"properties\":{\"runId\":{\"type\":\"STRING\","
            + "\"description\":\"The id of a run the member started.\"}},\"required\":[\"runId\"]}";

    private final WorkflowService workflows;
    private final WorkflowRunService runs;
    private final CardStore cards;
    private final ChatEvents events;
    private final JsonMapper json;

    ChatTools(WorkflowService workflows, WorkflowRunService runs, CardStore cards, ChatEvents events,
            JsonMapper json) {
        this.workflows = workflows;
        this.runs = runs;
        this.cards = cards;
        this.events = events;
        this.json = json;
    }

    public List<LlmTool> declarations() {
        return List.of(
                new LlmTool(FIND_WORKFLOWS, "Looks up the workflows the member can run: id, name, step types and "
                        + "what input each takes (text, image or product_video).", SCHEMAS_FIND),
                new LlmTool(PROPOSE_RUN, "Shows the member a confirmation card for a workflow. The run starts only "
                        + "when the member confirms; this call never starts it. Images the member attached to "
                        + "their message are used automatically.", SCHEMAS_PROPOSE),
                new LlmTool(RUN_STATUS, "Reads the status and step progress of a run the member started.",
                        SCHEMAS_STATUS));
    }

    /** Runs one call; every refusal and failure comes back as a JSON {@code error}, never as an exception. */
    public ToolResult call(ToolContext context, String name, String argsJson) {
        JsonNode args;
        try {
            args = argsJson == null || argsJson.isBlank() ? json.createObjectNode() : json.readTree(argsJson);
        } catch (JacksonException e) {
            return error("invalid-arguments", "The arguments are not valid JSON.");
        }
        try {
            return switch (name == null ? "" : name) {
                case FIND_WORKFLOWS -> findWorkflows(context, args);
                case PROPOSE_RUN -> proposeRun(context, args);
                case RUN_STATUS -> runStatus(context, args);
                default -> error("unknown-tool", "There is no tool with that name.");
            };
        } catch (RuntimeException e) {
            LOG.error("chat.tool outcome=error tool={}", name, e);
            return error("tool-failed", "The tool could not be run right now.");
        }
    }

    private ToolResult findWorkflows(ToolContext context, JsonNode args) {
        List<String> words = Arrays.stream(text(args, "query").orElse("").toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(word -> !word.isEmpty()).toList();
        ArrayNode found = json.createArrayNode();
        for (ChatWorkflowView view : workflows.listChatCatalog(context.ownerId())) {
            if (found.size() >= MAX_FOUND) {
                break;
            }
            if (words.isEmpty() || matches(view, words)) {
                ObjectNode item = found.addObject();
                item.put("id", view.id().toString());
                item.put("name", view.name());
                item.set("stepTypes", json.valueToTree(view.stepTypes()));
                item.put("inputKind", view.inputKind());
            }
        }
        ObjectNode result = json.createObjectNode();
        result.set("workflows", found);
        return new ToolResult(result.toString(), false);
    }

    private static boolean matches(ChatWorkflowView view, List<String> words) {
        String haystack = (view.name() + " " + String.join(" ", view.stepTypes())).toLowerCase(Locale.ROOT);
        return words.stream().anyMatch(haystack::contains);
    }

    private ToolResult proposeRun(ToolContext context, JsonNode args) {
        Optional<ChatWorkflowView> chosen = chosen(context, args);
        if (chosen.isEmpty()) {
            return refused("workflow_not_visible", "No such workflow is available to this member.", List.of());
        }
        ChatWorkflowView workflow = chosen.get();
        List<Attachment> uploads = List.of();
        ObjectNode productVideo = null;
        String input = null;
        if ("image".equals(workflow.inputKind())) {
            if (context.attachments().isEmpty() || context.attachments().size() > MAX_IMAGES) {
                return refused("attachments", "This workflow needs 1 to " + MAX_IMAGES
                        + " attached images; ask the member to attach them to their message.", List.of());
            }
            uploads = context.attachments();
        } else if ("product_video".equals(workflow.inputKind())) {
            if (context.attachments().size() != 1) {
                return refused("attachments", "Product Video needs exactly one attached product image; ask the "
                        + "member to attach it to their message.", List.of());
            }
            uploads = context.attachments();
            JsonNode raw = args.path("productVideo");
            String cta = text(raw, "cta").orElse(null);
            String price = text(raw, "price").orElse(null);
            String promotion = text(raw, "promotion").orElse(null);
            try {
                ProductVideoDraft draft = ProductVideoDraft.of(text(raw, "productName").orElse(null),
                        text(raw, "description").orElse(null), cta, price, promotion, ASPECT_RATIO);
                productVideo = json.createObjectNode();
                productVideo.put("productName", draft.productName());
                productVideo.put("description", draft.description());
                productVideo.put("cta", draft.cta());
                productVideo.put("price", draft.price());
                productVideo.put("promotion", draft.promotion());
            } catch (ConstraintViolationException e) {
                return refused("fields", "The product details are not valid: a name (up to 200 characters) and a "
                        + "description (up to 4000) are required; cta, price and promotion are optional.",
                        CardFields.refused(e, cta, price, promotion));
            }
        } else {
            input = text(args, "inputText").map(String::strip).orElse("");
            if (input.isEmpty() || input.codePointCount(0, input.length()) > MAX_TEXT_INPUT_CHARS) {
                return refused("input", "This workflow needs a text input of 1 to " + MAX_TEXT_INPUT_CHARS
                        + " characters.", List.of());
            }
        }

        ObjectNode card = json.createObjectNode();
        card.put("type", productVideo == null ? "run" : "product_video");
        card.put("workflowId", workflow.id().toString());
        card.put("workflowName", workflow.name());
        card.set("uploads", json.valueToTree(uploads));
        card.put("input", input);
        card.set("productVideo", productVideo);
        card.put("estimatedCredits", workflow.estimatedCredits());
        card.put("state", "pending");
        card.putNull("runId");
        Optional<StoredMessage> stored = cards.append(context.ownerId(), context.conversationId(), "card",
                card.toString());
        if (stored.isEmpty()) {
            return refused("conversation_gone", "The conversation no longer exists.", List.of());
        }
        announce(context.conversationId(), stored.get().id());
        ObjectNode result = json.createObjectNode();
        result.put("proposed", true);
        result.put("workflowName", workflow.name());
        result.put("message", "A confirmation card is now shown to the member. The run has NOT started; it starts "
                + "only when the member confirms.");
        return new ToolResult(result.toString(), true);
    }

    private void announce(UUID conversationId, UUID messageId) {
        try {
            events.updated(conversationId, messageId);
        } catch (RuntimeException e) {
            LOG.warn("chat.card outcome=publish_failed", e);
        }
    }

    private Optional<ChatWorkflowView> chosen(ToolContext context, JsonNode args) {
        UUID id;
        try {
            id = UUID.fromString(text(args, "workflowId").orElse(""));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return workflows.listChatCatalog(context.ownerId()).stream().filter(view -> view.id().equals(id))
                .findFirst();
    }

    private ToolResult runStatus(ToolContext context, JsonNode args) {
        UUID runId;
        try {
            runId = UUID.fromString(text(args, "runId").orElse(""));
        } catch (IllegalArgumentException e) {
            return error("not found", "No such run.");
        }
        RunView run;
        try {
            run = runs.getRun(context.ownerId(), runId);
        } catch (NotFoundException e) {
            return error("not found", "No such run.");
        }
        ObjectNode result = json.createObjectNode();
        result.put("status", run.status());
        ArrayNode steps = result.putArray("steps");
        run.steps().forEach(step -> {
            ObjectNode item = steps.addObject();
            item.put("order", step.stepOrder());
            item.put("type", step.type());
            item.put("status", step.status());
            if (step.progressPercent() == null) {
                item.putNull("progressPercent");
            } else {
                item.put("progressPercent", step.progressPercent());
            }
        });
        return new ToolResult(result.toString(), false);
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? Optional.of(value.asString()) : Optional.empty();
    }

    private ToolResult error(String code, String message) {
        ObjectNode result = json.createObjectNode();
        result.put("error", code);
        result.put("message", message);
        return new ToolResult(result.toString(), false);
    }

    private ToolResult refused(String reason, String message, List<String> fields) {
        ObjectNode result = json.createObjectNode();
        result.put("error", "chat-card-invalid");
        result.put("reason", reason);
        result.put("message", message);
        if (!fields.isEmpty()) {
            result.set("fields", json.valueToTree(fields));
        }
        return new ToolResult(result.toString(), false);
    }
}
