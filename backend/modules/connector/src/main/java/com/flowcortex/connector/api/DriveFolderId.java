package com.flowcortex.connector.api;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a Drive folder id out of what a member pasted: a folder link or the bare id. */
public final class DriveFolderId {

    private static final int MAX_LENGTH = 2048;
    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9_-]{10,}");
    private static final Pattern LINK = Pattern.compile("https?://\\S+?/folders/([A-Za-z0-9_-]{10,})(?:[/?#]\\S*)?");

    private DriveFolderId() {
    }

    /** The folder id in {@code value}, or empty when it is neither a folder link nor a bare id. */
    public static Optional<String> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String text = value.trim();
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher link = LINK.matcher(text);
        if (link.matches()) {
            return Optional.of(link.group(1));
        }
        return BARE.matcher(text).matches() ? Optional.of(text) : Optional.empty();
    }
}
