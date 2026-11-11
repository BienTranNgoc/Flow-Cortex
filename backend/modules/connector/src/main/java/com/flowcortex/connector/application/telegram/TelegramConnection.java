package com.flowcortex.connector.application.telegram;

import com.flowcortex.connector.application.telegram.TelegramRules.Chat;
import java.util.List;

/**
 * A member's Telegram connection as the API shows it: never a token. {@code state} is {@code connected}
 * when a default chat is set, else {@code awaiting_chat}.
 */
public record TelegramConnection(String provider, String botUsername, long botId, String state, List<Chat> chats,
        Long defaultChatId, boolean usageEnabled) {
}
