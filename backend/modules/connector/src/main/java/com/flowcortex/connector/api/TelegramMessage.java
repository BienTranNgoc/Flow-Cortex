package com.flowcortex.connector.api;

/**
 * One message to send through a member's bot.
 *
 * @param chatId the target chat; {@code null} means the member's default chat
 * @param text the message, or the caption of {@code media} (cut at 1024 characters); may be blank with media
 * @param media an optional file; {@code null} sends text only
 * @param parseMode how Telegram reads {@code text}; {@code null} means {@link ParseMode#HTML}
 */
public record TelegramMessage(Long chatId, String text, TelegramMedia media, ParseMode parseMode) {

    /** Telegram's text formatting modes. */
    public enum ParseMode {
        HTML("HTML"), MARKDOWN("Markdown"), MARKDOWN_V2("MarkdownV2");

        private final String wire;

        ParseMode(String wire) {
            this.wire = wire;
        }

        /** The value Telegram expects in {@code parse_mode}. */
        public String wire() {
            return wire;
        }
    }
}
