package com.flowcortex.connector.application.telegram;

import com.flowcortex.connector.application.telegram.TelegramRules.Chat;
import java.util.List;

/** The stored chats after a refresh. */
public record TelegramChatList(List<Chat> chats) {
}
