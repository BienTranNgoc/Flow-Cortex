package com.flowcortex.connector.api;

/** A file sent with a Telegram message, by URL: Telegram downloads it itself. */
public record TelegramMedia(Kind kind, String url) {

    /** Which Telegram send method carries the file. */
    public enum Kind { PHOTO, VIDEO, DOCUMENT }
}
