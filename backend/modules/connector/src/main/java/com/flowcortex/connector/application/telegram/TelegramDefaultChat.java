package com.flowcortex.connector.application.telegram;

/** The answer to setting the default chat: always {@code connected}, since a default chat is set. */
public record TelegramDefaultChat(String state, long defaultChatId) {
}
