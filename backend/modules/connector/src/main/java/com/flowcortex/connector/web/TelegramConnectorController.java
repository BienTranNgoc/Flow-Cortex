package com.flowcortex.connector.web;

import com.flowcortex.connector.application.telegram.TelegramChatList;
import com.flowcortex.connector.application.telegram.TelegramConnection;
import com.flowcortex.connector.application.telegram.TelegramConnectorService;
import com.flowcortex.connector.application.telegram.TelegramDefaultChat;
import com.flowcortex.connector.application.telegram.TelegramHealth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/connectors/telegram} — a member's own Telegram bot. No response carries the token. */
@RestController
@RequestMapping("/api/v1/connectors/telegram")
class TelegramConnectorController {

    /** The bot token is write-only: it never appears in a response, and not in this record's text form either. */
    record TelegramConnectRequest(@NotBlank @Size(max = 200) String botToken) {

        @Override
        public String toString() {
            return "TelegramConnectRequest[]";
        }
    }

    /** Give {@code chatId} to add one chat by id; leave the body empty to discover chats again. */
    record TelegramRefreshChatsRequest(Long chatId, @Size(max = 255) String title,
            @Pattern(regexp = "private|group|supergroup|channel") String type) {
    }

    record TelegramDefaultChatRequest(@NotNull Long chatId) {
    }

    private final TelegramConnectorService telegram;
    private final ConnectorMember member;

    TelegramConnectorController(TelegramConnectorService telegram, ConnectorMember member) {
        this.telegram = telegram;
        this.member = member;
    }

    @GetMapping
    TelegramConnection read(@AuthenticationPrincipal Jwt jwt) {
        return telegram.read(member.id(jwt));
    }

    @PostMapping("/connect")
    TelegramConnection connect(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TelegramConnectRequest request) {
        return telegram.connect(member.id(jwt), request.botToken());
    }

    @PostMapping("/refresh-chats")
    TelegramChatList refreshChats(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody(required = false) TelegramRefreshChatsRequest request) {
        TelegramRefreshChatsRequest body = request == null ? new TelegramRefreshChatsRequest(null, null, null) : request;
        return telegram.refreshChats(member.id(jwt), body.chatId(), body.title(), body.type());
    }

    @PatchMapping("/default-chat")
    TelegramDefaultChat setDefaultChat(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TelegramDefaultChatRequest request) {
        return telegram.setDefaultChat(member.id(jwt), request.chatId());
    }

    @GetMapping("/health")
    TelegramHealth health(@AuthenticationPrincipal Jwt jwt) {
        return telegram.health(member.id(jwt));
    }
}
