package com.flowcortex.chat.web;

import com.flowcortex.chat.cards.Attachment;
import com.flowcortex.chat.cards.CardService;
import com.flowcortex.chat.cards.CardService.Confirmation;
import com.flowcortex.chat.cards.CardService.Edits;
import com.flowcortex.chat.cards.ProductFields;
import com.flowcortex.chat.web.ConversationController.MessageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/chat/conversations/{conversationId}/cards/{messageId}/confirm} — the click that starts a run. */
@RestController
@RequestMapping("/api/v1/chat/conversations/{conversationId}/cards/{messageId}")
class CardController {

    private final CardService cards;
    private final ChatCaller caller;
    private final MessageViews views;

    CardController(CardService cards, ChatCaller caller, MessageViews views) {
        this.cards = cards;
        this.caller = caller;
        this.views = views;
    }

    @Schema(name = "ChatCardProductVideo")
    record ProductVideoEdit(String productName, String description, String cta, String price, String promotion) {
    }

    /**
     * {@code productVideo}, when sent, carries all five product fields as the member left them;
     * {@code attachments}, when sent, replaces the card's images.
     */
    @Schema(name = "ChatCardConfirm")
    record ConfirmRequest(Boolean acknowledgeCost, ProductVideoEdit productVideo,
            @ArraySchema(maxItems = SendInput.MAX_ATTACHMENTS) List<Attachment> attachments) {
    }

    @Schema(name = "ChatCardConfirmed")
    record ConfirmedView(UUID runId, MessageView card) {
    }

    @Operation(operationId = "confirmChatCard")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "chat-card-invalid (errors per refused field)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "402", description = "INSUFFICIENT_CREDITS (balance, required, shortfall); "
            + "no run is started", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "chat-conversation-not-found or chat-message-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "chat-card-images-expired",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    ConfirmedView confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId,
            @PathVariable UUID messageId, @RequestBody(required = false) ConfirmRequest body) {
        UUID owner = caller.require(jwt).id();
        ConfirmRequest request = body == null ? new ConfirmRequest(null, null, null) : body;
        ProductVideoEdit edit = request.productVideo();
        ProductFields fields = edit == null ? null : new ProductFields(edit.productName(), edit.description(),
                edit.cta(), edit.price(), edit.promotion());
        Confirmation confirmation = cards.confirm(owner, conversationId, messageId,
                new Edits(Boolean.TRUE.equals(request.acknowledgeCost()), fields, request.attachments()));
        return new ConfirmedView(confirmation.runId(), views.view(confirmation.card()));
    }
}
