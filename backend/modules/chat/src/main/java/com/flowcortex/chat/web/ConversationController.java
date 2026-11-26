package com.flowcortex.chat.web;

import com.flowcortex.chat.cards.Attachment;
import com.flowcortex.chat.config.ChatSwitch;
import com.flowcortex.chat.conversation.ConversationQuery;
import com.flowcortex.chat.conversation.ConversationStore;
import com.flowcortex.chat.conversation.ConversationStore.ConversationPage;
import com.flowcortex.chat.conversation.ConversationStore.GroupCounts;
import com.flowcortex.chat.conversation.ConversationStore.ListedConversation;
import com.flowcortex.chat.conversation.ConversationStore.StoredConversation;
import com.flowcortex.chat.turn.TurnService;
import com.flowcortex.chat.turn.TurnService.CreatedConversation;
import com.flowcortex.shared.web.LocalizedMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/v1/chat/conversations} — the signed-in member's conversations. A conversation that is
 * not the caller's answers exactly like one that does not exist.
 */
@RestController
@RequestMapping("/api/v1/chat/conversations")
class ConversationController {

    static final int MAX_TITLE_CHARS = 80;

    private final ConversationStore store;
    private final ChatCaller caller;
    private final TurnService turns;
    private final ChatSwitch chatSwitch;
    private final MessageViews views;

    ConversationController(ConversationStore store, ChatCaller caller, TurnService turns,
            ChatSwitch chatSwitch, MessageViews views) {
        this.store = store;
        this.caller = caller;
        this.turns = turns;
        this.chatSwitch = chatSwitch;
        this.views = views;
    }

    @Schema(name = "ChatConversationCreate")
    record CreateRequest(@Schema(maxLength = MAX_TITLE_CHARS) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FirstMessage firstMessage) {
    }

    @Schema(name = "ChatFirstMessage")
    record FirstMessage(
            @Schema(minLength = 1, maxLength = SendInput.MAX_TEXT_CHARS) String text,
            @ArraySchema(maxItems = SendInput.MAX_ATTACHMENTS) List<Attachment> attachments) {
    }

    /** The conversation with the first message it was created for and the answer being written to it. */
    @Schema(name = "ChatConversationCreated")
    record CreatedView(UUID id, String title, Instant lastActivityAt, Instant createdAt, boolean pinned,
            @Schema(nullable = true) UUID folderId, MessageView userMessage, UUID assistantMessageId) {
    }

    @Schema(name = "ChatConversationTitle")
    record TitleRequest(@Schema(maxLength = MAX_TITLE_CHARS) String title) {
    }

    @Schema(name = "ChatConversationPin")
    record PinRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Boolean pinned) {
    }

    @Schema(name = "ChatConversation")
    record ConversationView(UUID id, String title, Instant lastActivityAt, Instant createdAt, boolean pinned,
            @Schema(nullable = true) UUID folderId) {

        static ConversationView of(StoredConversation conversation) {
            return new ConversationView(conversation.id(), conversation.title(), conversation.lastActivityAt(),
                    conversation.createdAt(), conversation.pinned(), conversation.folderId());
        }
    }

    /** A list row: the conversation plus a preview of its latest text message, null when it has none. */
    @Schema(name = "ChatConversationListItem")
    record ConversationListItem(UUID id, String title, Instant lastActivityAt, Instant createdAt, boolean pinned,
            @Schema(nullable = true) UUID folderId, @Schema(maxLength = ConversationStore.PREVIEW_CHARS, nullable = true)
            String preview, @Schema(description = "True when the latest text was longer than the preview cap")
            boolean previewTruncated) {

        static ConversationListItem of(ListedConversation listed) {
            StoredConversation conversation = listed.conversation();
            return new ConversationListItem(conversation.id(), conversation.title(), conversation.lastActivityAt(),
                    conversation.createdAt(), conversation.pinned(), conversation.folderId(), listed.preview(),
                    listed.previewTruncated());
        }
    }

    @Schema(name = "ChatConversationList")
    record ConversationList(List<ConversationListItem> items,
            @Schema(nullable = true, description = "Opaque cursor of the next page; null on the last page")
            String nextCursor, boolean chatEnabled) {
    }

    @Schema(name = "ChatConversationCounts")
    record ConversationCounts(int total, int pinned, int today, int last7Days, int earlier) {
    }

    @Schema(name = "ChatMessage")
    record MessageView(UUID id, int seq, String role, String kind, String body,
            @Schema(implementation = Object.class, types = {"object", "null"}, description = "Card or run payload; shape depends on kind")
            JsonNode payload, String status,
            Instant createdAt) {
    }

    /** Not gated by the chat switch: the history stays readable and organizable while sends are off. */
    @Operation(operationId = "listChatConversations")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed; a field error per refused parameter",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ConversationList list(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "30"))
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(allowableValues = {"newest", "oldest"}, defaultValue = "newest"))
            @RequestParam(required = false) String sort,
            @Parameter(schema = @Schema(allowableValues = {"only", "exclude"}))
            @RequestParam(required = false) String pinned,
            @Parameter(description = "A folder id, or none for conversations outside every folder")
            @RequestParam(required = false) String folder,
            @Parameter(schema = @Schema(maxLength = ConversationQuery.MAX_QUERY_CHARS))
            @RequestParam(required = false) String q) {
        UUID owner = caller.require(jwt).id();
        ConversationPage page = store.list(owner, ConversationQuery.parse(limit, cursor, sort, pinned, folder, q));
        return new ConversationList(page.items().stream().map(ConversationListItem::of).toList(),
                page.next() == null ? null : page.next().encode(), chatSwitch.available());
    }

    @Operation(operationId = "countChatConversations")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed; a field error per refused parameter",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/counts", produces = MediaType.APPLICATION_JSON_VALUE)
    ConversationCounts counts(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q,
            @Parameter(description = "A folder id, or none for conversations outside every folder")
            @RequestParam(required = false) String folder,
            @Parameter(required = true, schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(required = false) String todayStart,
            @Parameter(required = true, schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(required = false) String weekStart) {
        UUID owner = caller.require(jwt).id();
        ConversationQuery filter = ConversationQuery.parse(null, null, null, null, folder, q);
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        Instant today = boundary(todayStart, "todayStart", errors);
        Instant week = boundary(weekStart, "weekStart", errors);
        if (today != null && week != null && week.isAfter(today)) {
            errors.put("weekStart", new LocalizedMessage("chat.counts.boundaries.invalid"));
        }
        if (!errors.isEmpty()) {
            throw new ChatProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                    new LocalizedMessage("problem.validationFailed")).withFieldErrors(errors);
        }
        GroupCounts counts = store.counts(owner, filter.words(), filter.folder(), today, week);
        return new ConversationCounts(counts.total(), counts.pinned(), counts.today(), counts.last7Days(),
                counts.earlier());
    }

    @Operation(operationId = "pinChatConversation")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "chat-conversation-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(value = "/{id}/pin", produces = MediaType.APPLICATION_JSON_VALUE)
    ConversationView pin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody PinRequest body) {
        UUID owner = caller.require(jwt).id();
        if (body.pinned() == null) {
            throw new ChatProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                    new LocalizedMessage("problem.validationFailed")).withFieldErrors(
                    Map.of("pinned", new LocalizedMessage("chat.pin.invalid")));
        }
        return store.setPinned(owner, id, body.pinned()).map(ConversationView::of)
                .orElseThrow(ChatProblemException::conversationNotFound);
    }

    private static Instant boundary(String raw, String field, Map<String, LocalizedMessage> errors) {
        Instant parsed = null;
        if (raw != null) {
            try {
                parsed = Instant.parse(raw);
            } catch (DateTimeParseException e) {
                parsed = null;
            }
        }
        if (parsed == null) {
            errors.put(field, new LocalizedMessage("chat.counts.boundaries.invalid"));
        }
        return parsed;
    }

    @Operation(operationId = "createChatConversation")
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "idempotency-key-reused",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "chat-quota (resumeAt); no conversation is created",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "chat-disabled; no conversation is created",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    CreatedView create(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) CreateRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, Locale locale) {
        UUID owner = caller.require(jwt).id();
        String given = body == null || body.title() == null ? "" : body.title().strip();
        FirstMessage first = body == null || body.firstMessage() == null ? new FirstMessage(null, null)
                : body.firstMessage();
        String key = SendInput.key(idempotencyKey);
        SendInput.Message message = SendInput.message(first.text(), first.attachments());
        CreatedConversation created = turns.create(owner, given.isEmpty() ? null : validTitle(given), key,
                message.text(), message.attachments(), SendInput.language(locale));
        StoredConversation conversation = created.conversation();
        return new CreatedView(conversation.id(), conversation.title(), conversation.lastActivityAt(),
                conversation.createdAt(), conversation.pinned(), conversation.folderId(),
                views.view(created.first().userMessage()),
                created.first().assistantMessageId());
    }

    @Operation(operationId = "renameChatConversation")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "chat-conversation-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ConversationView rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody TitleRequest body) {
        UUID owner = caller.require(jwt).id();
        String title = validTitle(body.title() == null ? "" : body.title().strip());
        return store.rename(owner, id, title).map(ConversationView::of)
                .orElseThrow(ChatProblemException::conversationNotFound);
    }

    @Operation(operationId = "deleteChatConversation")
    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "404", description = "chat-conversation-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!store.delete(caller.require(jwt).id(), id)) {
            throw ChatProblemException.conversationNotFound();
        }
    }

    @Operation(operationId = "listChatMessages")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "chat-conversation-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    List<MessageView> messages(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return store.messages(caller.require(jwt).id(), id)
                .orElseThrow(ChatProblemException::conversationNotFound)
                .stream().map(views::view).toList();
    }

    private static String validTitle(String title) {
        if (title.isEmpty() || title.codePointCount(0, title.length()) > MAX_TITLE_CHARS) {
            throw new ChatProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                    new LocalizedMessage("problem.validationFailed")).withFieldErrors(
                    Map.of("title", new LocalizedMessage("chat.conversation.title.invalid", MAX_TITLE_CHARS)));
        }
        return title;
    }
}
