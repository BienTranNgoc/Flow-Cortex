package com.flowcortex.chat.web;

import com.flowcortex.chat.cards.Attachment;
import com.flowcortex.chat.config.ChatSwitch;
import com.flowcortex.chat.conversation.ConversationStore;
import com.flowcortex.chat.conversation.ConversationStore.StoredConversation;
import com.flowcortex.chat.turn.TurnService;
import com.flowcortex.chat.turn.TurnService.CreatedConversation;
import com.flowcortex.shared.web.LocalizedMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.MessageSource;
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
    private final MessageSource messages;
    private final TurnService turns;
    private final ChatSwitch chatSwitch;
    private final MessageViews views;

    ConversationController(ConversationStore store, ChatCaller caller, MessageSource messages, TurnService turns,
            ChatSwitch chatSwitch, MessageViews views) {
        this.store = store;
        this.caller = caller;
        this.messages = messages;
        this.turns = turns;
        this.chatSwitch = chatSwitch;
        this.views = views;
    }

    @Schema(name = "ChatConversationCreate")
    record CreateRequest(@Schema(maxLength = MAX_TITLE_CHARS) String title, FirstMessage firstMessage) {
    }

    @Schema(name = "ChatFirstMessage")
    record FirstMessage(
            @Schema(minLength = 1, maxLength = SendInput.MAX_TEXT_CHARS) String text,
            @ArraySchema(maxItems = SendInput.MAX_ATTACHMENTS) List<Attachment> attachments) {
    }

    /** {@code userMessage} and {@code assistantMessageId} are set when the create carried a first message. */
    @Schema(name = "ChatConversationCreated")
    record CreatedView(UUID id, String title, Instant lastActivityAt, Instant createdAt, MessageView userMessage,
            UUID assistantMessageId) {
    }

    @Schema(name = "ChatConversationTitle")
    record TitleRequest(@Schema(maxLength = MAX_TITLE_CHARS) String title) {
    }

    @Schema(name = "ChatConversation")
    record ConversationView(UUID id, String title, Instant lastActivityAt, Instant createdAt) {

        static ConversationView of(StoredConversation conversation) {
            return new ConversationView(conversation.id(), conversation.title(), conversation.lastActivityAt(),
                    conversation.createdAt());
        }
    }

    @Schema(name = "ChatConversationList")
    record ConversationList(List<ConversationView> items, boolean chatEnabled) {
    }

    @Schema(name = "ChatMessage")
    record MessageView(UUID id, int seq, String role, String kind, String body,
            @Schema(implementation = Object.class, types = {"object", "null"}, description = "Card or run payload; shape depends on kind")
            JsonNode payload, String status,
            Instant createdAt) {
    }

    @Operation(operationId = "listChatConversations")
    @ApiResponse(responseCode = "200")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ConversationList list(@AuthenticationPrincipal Jwt jwt) {
        UUID owner = caller.require(jwt).id();
        return new ConversationList(store.list(owner).stream().map(ConversationView::of).toList(),
                chatSwitch.available());
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
        FirstMessage first = body == null ? null : body.firstMessage();
        if (first == null) {
            String title = given.isEmpty() ? messages.getMessage("chat.conversation.untitled", null, locale)
                    : validTitle(given);
            return created(store.create(owner, title), null);
        }
        String key = SendInput.key(idempotencyKey);
        SendInput.Message message = SendInput.message(first.text(), first.attachments());
        CreatedConversation created = turns.create(owner, given.isEmpty() ? null : validTitle(given), key,
                message.text(), message.attachments(), SendInput.language(locale));
        return created(created.conversation(), created.first());
    }

    private CreatedView created(StoredConversation conversation, TurnService.Started first) {
        return new CreatedView(conversation.id(), conversation.title(), conversation.lastActivityAt(),
                conversation.createdAt(), first == null ? null : views.view(first.userMessage()),
                first == null ? null : first.assistantMessageId());
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
