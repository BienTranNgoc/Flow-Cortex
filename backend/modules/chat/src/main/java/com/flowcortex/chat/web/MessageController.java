package com.flowcortex.chat.web;

import com.flowcortex.chat.cards.Attachment;
import com.flowcortex.chat.turn.TurnService;
import com.flowcortex.chat.turn.TurnService.Started;
import com.flowcortex.chat.web.ConversationController.MessageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/chat/conversations/{conversationId}/messages} — send a message, stop the running answer,
 * retry a failed or stopped one. Reading the thread stays on {@link ConversationController}.
 */
@RestController
@RequestMapping("/api/v1/chat/conversations/{conversationId}/messages")
class MessageController {

    private final TurnService turns;
    private final ChatCaller caller;
    private final MessageViews views;

    MessageController(TurnService turns, ChatCaller caller, MessageViews views) {
        this.turns = turns;
        this.caller = caller;
        this.views = views;
    }

    @Schema(name = "ChatSendMessage")
    record SendRequest(
            @Schema(minLength = 1, maxLength = SendInput.MAX_TEXT_CHARS) String text,
            @ArraySchema(maxItems = SendInput.MAX_ATTACHMENTS) List<Attachment> attachments) {
    }

    @Schema(name = "ChatMessageSent")
    record SentView(MessageView userMessage, UUID assistantMessageId) {
    }

    static SentView sent(Started started, MessageViews views) {
        return new SentView(views.view(started.userMessage()), started.assistantMessageId());
    }

    @Operation(operationId = "sendChatMessage")
    @ApiResponse(responseCode = "202")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.text, errors.attachments, "
            + "errors.Idempotency-Key)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "chat-conversation-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "chat-turn-in-progress",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "idempotency-key-reused",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "chat-quota (resumeAt)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "chat-disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    SentView send(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody SendRequest body, Locale locale) {
        UUID owner = caller.require(jwt).id();
        String key = SendInput.key(idempotencyKey);
        SendInput.Message message = SendInput.message(body.text(), body.attachments());
        return sent(turns.send(owner, conversationId, key, message.text(), message.attachments(),
                SendInput.language(locale)), views);
    }

    @Operation(operationId = "stopChatMessage")
    @ApiResponse(responseCode = "202")
    @ApiResponse(responseCode = "404", description = "chat-conversation-not-found or chat-message-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{messageId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void stop(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId, @PathVariable UUID messageId) {
        turns.stop(caller.require(jwt).id(), conversationId, messageId);
    }

    @Operation(operationId = "retryChatMessage")
    @ApiResponse(responseCode = "202")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.Idempotency-Key)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "chat-conversation-not-found or chat-message-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "chat-turn-in-progress or chat-turn-not-retryable",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "idempotency-key-reused",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "chat-quota (resumeAt)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "chat-disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/{messageId}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    SentView retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId,
            @PathVariable UUID messageId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, Locale locale) {
        UUID owner = caller.require(jwt).id();
        return sent(turns.retry(owner, conversationId, messageId, SendInput.key(idempotencyKey),
                SendInput.language(locale)), views);
    }
}
