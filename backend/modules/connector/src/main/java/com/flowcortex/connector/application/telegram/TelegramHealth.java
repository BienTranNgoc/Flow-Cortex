package com.flowcortex.connector.application.telegram;

/** Whether Telegram still accepts the stored token; {@code reason} is {@code tokenInvalid} when it does not. */
public record TelegramHealth(boolean valid, String provider, String botUsername, String reason) {
}
