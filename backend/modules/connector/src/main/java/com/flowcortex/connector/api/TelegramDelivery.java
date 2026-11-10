package com.flowcortex.connector.api;

/**
 * What Telegram accepted.
 *
 * @param providerMessageId Telegram's id of the posted message
 * @param recipient {@code <chat title> (<chat id>)}, or the bare chat id for a chat not in the stored list
 */
public record TelegramDelivery(long providerMessageId, String recipient) {
}
