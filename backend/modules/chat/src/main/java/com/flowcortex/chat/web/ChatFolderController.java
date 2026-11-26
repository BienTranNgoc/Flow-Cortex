package com.flowcortex.chat.web;

import com.flowcortex.chat.config.ChatHistoryProperties;
import com.flowcortex.chat.folder.FolderName;
import com.flowcortex.chat.folder.FolderStore;
import com.flowcortex.chat.folder.FolderStore.MoveResult;
import com.flowcortex.chat.folder.FolderStore.StoredFolder;
import com.flowcortex.chat.web.ConversationController.ConversationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/chat/folders} and the move route of a conversation. Not gated by the chat switch: nothing here
 * calls the model, so a member can organize while sends are off. A folder or conversation that is not the
 * caller's answers exactly like one that does not exist.
 */
@RestController
@RequestMapping("/api/v1/chat")
class ChatFolderController {

    private final FolderStore store;
    private final ChatCaller caller;
    private final ChatHistoryProperties history;

    ChatFolderController(FolderStore store, ChatCaller caller, ChatHistoryProperties history) {
        this.store = store;
        this.caller = caller;
        this.history = history;
    }

    @Schema(name = "ChatFolderName")
    record NameRequest(@Schema(minLength = 1, maxLength = FolderName.MAX_CHARS) String name) {
    }

    @Schema(name = "ChatConversationFolder")
    record MoveRequest(@Schema(nullable = true, description = "The folder to file into; null returns the "
            + "conversation to Recent") UUID folderId) {
    }

    @Schema(name = "ChatFolder")
    record FolderView(UUID id, String name, int conversationCount, Instant createdAt) {

        static FolderView of(StoredFolder folder) {
            return new FolderView(folder.id(), folder.name(), folder.conversationCount(), folder.createdAt());
        }
    }

    @Schema(name = "ChatFolderList")
    record FolderList(List<FolderView> items) {
    }

    @Operation(operationId = "listChatFolders")
    @ApiResponse(responseCode = "200")
    @GetMapping(value = "/folders", produces = MediaType.APPLICATION_JSON_VALUE)
    FolderList list(@AuthenticationPrincipal Jwt jwt) {
        return new FolderList(store.list(caller.require(jwt).id()).stream().map(FolderView::of).toList());
    }

    @Operation(operationId = "createChatFolder")
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.name)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "chat-folder-limit; the member holds the most folders allowed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/folders", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    FolderView create(@AuthenticationPrincipal Jwt jwt, @RequestBody NameRequest body) {
        UUID owner = caller.require(jwt).id();
        FolderName name = FolderName.parse(body.name());
        return store.create(owner, name, history.maxFolders()).map(FolderView::of)
                .orElseThrow(() -> ChatProblemException.folderLimit(history.maxFolders()));
    }

    @Operation(operationId = "renameChatFolder")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.name)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "chat-folder-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(value = "/folders/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    FolderView rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody NameRequest body) {
        UUID owner = caller.require(jwt).id();
        FolderName name = FolderName.parse(body.name());
        return store.rename(owner, id, name).map(FolderView::of).orElseThrow(ChatProblemException::folderNotFound);
    }

    @Operation(operationId = "deleteChatFolder", description = "The folder's conversations are kept and return "
            + "to Recent with their pinned state.")
    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "404", description = "chat-folder-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/folders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!store.delete(caller.require(jwt).id(), id)) {
            throw ChatProblemException.folderNotFound();
        }
    }

    @Operation(operationId = "moveChatConversation")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "chat-conversation-not-found or chat-folder-not-found; "
            + "nothing moves",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping(value = "/conversations/{id}/folder", produces = MediaType.APPLICATION_JSON_VALUE)
    ConversationView move(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody MoveRequest body) {
        MoveResult moved = store.move(caller.require(jwt).id(), id, body.folderId());
        return switch (moved.missing()) {
            case NONE -> ConversationView.of(moved.conversation());
            case CONVERSATION -> throw ChatProblemException.conversationNotFound();
            case FOLDER -> throw ChatProblemException.folderNotFound();
        };
    }
}
