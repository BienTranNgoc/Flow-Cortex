package com.flowcortex.chat.cards;

import com.flowcortex.chat.conversation.ConversationStore.StoredMessage;
import com.flowcortex.chat.turn.ChatEvents;
import com.flowcortex.chat.web.ChatProblemException;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.workflow.api.ProductVideoDraft;
import com.flowcortex.workflow.api.ProductVideoRuns;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Confirms a card: starts its run as the member, outside any database transaction, under the idempotency key
 * {@code chat-card:<messageId>}, then records the run on the card and in the thread in one short transaction.
 * A second confirm, even a concurrent one, gets the same run and appends nothing.
 */
@Service("chatCardService")
public class CardService {

    static final int MAX_IMAGES = 10;
    static final String ASPECT_RATIO = "9:16";
    static final String KEY_PREFIX = "chat-card:";

    private static final Logger LOG = LoggerFactory.getLogger(CardService.class);

    private final CardStore cards;
    private final WorkflowRunService runs;
    private final ProductVideoRuns productVideoRuns;
    private final ChatEvents events;
    private final JsonMapper json;
    private final TransactionTemplate transactions;

    CardService(CardStore cards, WorkflowRunService runs, ProductVideoRuns productVideoRuns, ChatEvents events,
            JsonMapper json, PlatformTransactionManager transactionManager) {
        this.cards = cards;
        this.runs = runs;
        this.productVideoRuns = productVideoRuns;
        this.events = events;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * What the member changed before confirming. {@code productVideo} replaces all five product fields when
     * present; {@code attachments} replaces the card's images when present (a text card ignores them).
     */
    public record Edits(boolean acknowledgeCost, ProductFields productVideo, List<Attachment> attachments) {
    }

    /** The run the card started and the card as stored now. */
    public record Confirmation(UUID runId, StoredMessage card) {
    }

    /**
     * @throws ChatProblemException 404 for a conversation or card that is not the member's, 400
     *         {@code chat-card-invalid} for refused edits, 409 {@code chat-card-images-expired} when an upload is
     *         gone or is not the member's
     */
    public Confirmation confirm(UUID ownerId, UUID conversationId, UUID messageId, Edits edits) {
        if (!cards.conversationOwned(ownerId, conversationId)) {
            throw ChatProblemException.conversationNotFound();
        }
        StoredMessage stored = cards.card(conversationId, messageId)
                .orElseThrow(ChatProblemException::messageNotFound);
        JsonNode card = payload(stored);
        if (CardStore.STARTED.equals(card.path("state").asString())) {
            return started(stored, card);
        }

        boolean video = "product_video".equals(card.path("type").asString());
        List<Attachment> uploads = Attachments.fromNode(card.path("uploads"));
        boolean imageCard = video || !uploads.isEmpty();
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        ObjectNode patch = json.createObjectNode();

        if (imageCard && edits.attachments() != null) {
            Optional<List<Attachment>> replacement = Attachments.clean(edits.attachments(), 1,
                    video ? 1 : MAX_IMAGES);
            if (replacement.isPresent()) {
                uploads = replacement.get();
                patch.set("uploads", json.valueToTree(uploads));
            } else {
                errors.put(CardFields.ATTACHMENTS, attachmentsInvalid(video));
            }
        }
        ProductVideoDraft draft = null;
        if (video) {
            ProductFields fields = edits.productVideo() != null ? edits.productVideo()
                    : fieldsOf(card.path("productVideo"));
            try {
                draft = ProductVideoDraft.of(fields.productName(), fields.description(), fields.cta(),
                        fields.price(), fields.promotion(), ASPECT_RATIO);
                if (edits.productVideo() != null) {
                    patch.set("productVideo", json.valueToTree(new ProductFields(draft.productName(),
                            draft.description(), draft.cta(), draft.price(), draft.promotion())));
                }
            } catch (ConstraintViolationException e) {
                errors.putAll(fieldErrors(e, fields, true));
            }
        }
        if (!errors.isEmpty()) {
            throw ChatProblemException.cardInvalid(errors);
        }
        if (patch.size() > 0) {
            patch.put("state", CardStore.PENDING);
            if (cards.amendOpen(messageId, patch.toString()) == 0) {
                return cards.card(conversationId, messageId).map(row -> started(row, payload(row)))
                        .orElseThrow(ChatProblemException::messageNotFound);
            }
        }

        RunView run = start(ownerId, conversationId, messageId, card, draft, uploads, edits.acknowledgeCost());
        ObjectNode startedWith = json.createObjectNode();
        if (imageCard) {
            startedWith.set("uploads", json.valueToTree(uploads));
        }
        if (draft != null) {
            startedWith.set("productVideo", json.valueToTree(new ProductFields(draft.productName(),
                    draft.description(), draft.cta(), draft.price(), draft.promotion())));
        }
        return recorded(ownerId, conversationId, messageId, run.id(), startedWith.toString());
    }

    private RunView start(UUID ownerId, UUID conversationId, UUID messageId, JsonNode card, ProductVideoDraft draft,
            List<Attachment> uploads, boolean acknowledgeCost) {
        String key = KEY_PREFIX + messageId;
        List<UUID> uploadIds = uploads.stream().map(Attachment::uploadId).toList();
        try {
            if (draft != null) {
                return productVideoRuns.start(ownerId, draft, uploadIds, acknowledgeCost, key);
            }
            UUID workflowId = UUID.fromString(card.path("workflowId").asString());
            if (uploads.isEmpty()) {
                return runs.startRun(ownerId, workflowId, card.path("input").asString(), null, null, null,
                        acknowledgeCost, key);
            }
            String names = String.join(", ", uploads.stream().map(Attachment::fileName).toList());
            return runs.startRun(ownerId, workflowId, null, null, uploadIds, names, acknowledgeCost, key);
        } catch (UploadRejectedException e) {
            if (cards.markImagesExpired(messageId) == 1) {
                announce(conversationId, messageId);
            }
            throw ChatProblemException.cardImagesExpired();
        } catch (ConstraintViolationException e) {
            throw ChatProblemException.cardInvalid(fieldErrors(e, fieldsOf(card.path("productVideo")),
                    draft != null));
        }
    }

    /**
     * Marks the card started with the edits its run started with and appends the run message, once; a confirm
     * that lost the race changes nothing.
     */
    private Confirmation recorded(UUID ownerId, UUID conversationId, UUID messageId, UUID runId,
            String startedWithJson) {
        Optional<StoredMessage> runMessage = transactions.execute(status -> {
            if (cards.markStarted(messageId, runId, startedWithJson) == 0) {
                return Optional.empty();
            }
            return cards.append(ownerId, conversationId, "run", runPayload(runId));
        });
        if (runMessage.isPresent()) {
            announce(conversationId, messageId);
            announce(conversationId, runMessage.get().id());
        }
        StoredMessage card = cards.card(conversationId, messageId)
                .orElseThrow(ChatProblemException::messageNotFound);
        return new Confirmation(runId, card);
    }

    private Confirmation started(StoredMessage stored, JsonNode card) {
        return new Confirmation(UUID.fromString(card.path("runId").asString()), stored);
    }

    private String runPayload(UUID runId) {
        ObjectNode payload = json.createObjectNode();
        payload.put("runId", runId.toString());
        return payload.toString();
    }

    private JsonNode payload(StoredMessage message) {
        try {
            return json.readTree(message.payload());
        } catch (JacksonException e) {
            throw new IllegalStateException("chat_message.payload of a card is not JSON", e);
        }
    }

    private static ProductFields fieldsOf(JsonNode node) {
        return new ProductFields(textOrNull(node, "productName"), textOrNull(node, "description"),
                textOrNull(node, "cta"), textOrNull(node, "price"), textOrNull(node, "promotion"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static LocalizedMessage attachmentsInvalid(boolean video) {
        return new LocalizedMessage("chat.card.attachments.invalid", video ? "1" : "1-" + MAX_IMAGES);
    }

    /** Localized messages for the fields a validation refused; never the validator's own wording. */
    private static Map<String, LocalizedMessage> fieldErrors(ConstraintViolationException e, ProductFields fields,
            boolean video) {
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        for (String field : CardFields.refused(e, fields.cta(), fields.price(), fields.promotion())) {
            errors.put(field, switch (field) {
                case CardFields.PRODUCT_NAME -> new LocalizedMessage("chat.card.productName.invalid", 200);
                case CardFields.DESCRIPTION -> new LocalizedMessage("chat.card.description.invalid", 4000);
                case CardFields.CTA, CardFields.PROMOTION -> new LocalizedMessage("chat.card.optional.invalid", 500);
                case CardFields.PRICE -> new LocalizedMessage("chat.card.optional.invalid", 200);
                case CardFields.ATTACHMENTS -> attachmentsInvalid(video);
                default -> new LocalizedMessage("chat.card.field.invalid");
            });
        }
        if (errors.isEmpty()) {
            LOG.warn("chat.card outcome=unmapped_violation", e);
            errors.put("card", new LocalizedMessage("chat.card.field.invalid"));
        }
        return errors;
    }

    private void announce(UUID conversationId, UUID messageId) {
        try {
            events.updated(conversationId, messageId);
        } catch (RuntimeException e) {
            LOG.warn("chat.card outcome=publish_failed", e);
        }
    }
}
