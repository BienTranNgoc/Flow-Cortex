package com.flowcortex.connector.application.telegram;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.TelegramMedia;
import com.flowcortex.connector.api.TelegramMessage;
import com.flowcortex.shared.web.LocalizedMessage;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The pure rules of the Telegram connector: no I/O, no clock, every decision a function of its input. */
public final class TelegramRules {

    /** A chat the bot can post to, as stored in the connection's metadata. */
    public record Chat(long id, String title, String type) {
    }

    /**
     * Why Telegram refused a send. {@link Kind#REFUSED} carries a {@code reason} (also the last segment of its
     * message key) and, for {@code rejected}, Telegram's own {@code description}.
     */
    public record Refusal(Kind kind, String reason, boolean retryable, Duration retryAfter, String description) {

        /** Which typed failure the refusal becomes. */
        public enum Kind { REFUSED, RATE_LIMITED, UPSTREAM_UNAVAILABLE }

        /** The typed failure, with a literal message key per reason so the bundle guard can see it. */
        public ConnectorException exception() {
            return switch (kind) {
                case UPSTREAM_UNAVAILABLE -> ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE);
                case RATE_LIMITED -> new ConnectorException(Reason.RATE_LIMITED, true,
                        new LocalizedMessage("connector.telegram.send.rateLimited", retryAfter.toSeconds()), reason,
                        retryAfter);
                case REFUSED -> new ConnectorException(Reason.REFUSED, false, refusedMessage(), reason, null);
            };
        }

        private LocalizedMessage refusedMessage() {
            return switch (reason) {
                case "recipientBlocked" -> new LocalizedMessage("connector.telegram.send.recipientBlocked");
                case "botNotInChat" -> new LocalizedMessage("connector.telegram.send.botNotInChat");
                case "chatNotFound" -> new LocalizedMessage("connector.telegram.send.chatNotFound");
                case "fileTooLarge" -> new LocalizedMessage("connector.telegram.send.fileTooLarge");
                case "tokenInvalid" -> new LocalizedMessage("connector.telegram.send.tokenInvalid");
                default -> new LocalizedMessage("connector.telegram.send.rejected",
                        description == null ? "" : description);
            };
        }
    }

    static final int MAX_TEXT = 4096;
    static final int MAX_CAPTION = 1024;

    private static final Pattern TOKEN = Pattern.compile("\\d+:[A-Za-z0-9_-]{30,}");
    private static final Pattern RETRY_AFTER = Pattern.compile("retry after (\\d+)");
    private static final Set<String> CHAT_TYPES = Set.of("private", "group", "supergroup", "channel");
    private static final List<String> UPDATE_KINDS =
            List.of("message", "edited_message", "channel_post", "edited_channel_post", "my_chat_member");
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);

    private TelegramRules() {
    }

    /** The trimmed token when it has the shape {@code <digits>:<30+ of A-Z a-z 0-9 _ ->}. */
    public static Optional<String> normalizeToken(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String token = raw.trim();
        return TOKEN.matcher(token).matches() ? Optional.of(token) : Optional.empty();
    }

    /** Stored chats first, in their order; an incoming entry replaces the stored one of its id; nothing is dropped. */
    public static List<Chat> mergeChats(List<Chat> stored, List<Chat> incoming) {
        Map<Long, Chat> merged = new LinkedHashMap<>();
        stored.forEach(chat -> merged.put(chat.id(), chat));
        incoming.forEach(chat -> merged.put(chat.id(), chat));
        return List.copyOf(merged.values());
    }

    /** A chat the member entered by id; a blank title becomes {@code Chat <id>}, a blank type {@code group}. */
    public static Chat manualChat(long id, String title, String type) {
        String cleanTitle = title == null || title.isBlank() ? "Chat " + id : title.trim();
        return new Chat(id, cleanTitle, chatType(type));
    }

    /** {@code title}, else {@code first_name}, else {@code @username}, else {@code Chat <id>}. */
    public static String chatTitle(Map<?, ?> chat) {
        String title = text(chat.get("title"));
        if (title != null) {
            return title;
        }
        String firstName = text(chat.get("first_name"));
        if (firstName != null) {
            return firstName;
        }
        String username = text(chat.get("username"));
        if (username != null) {
            return "@" + username;
        }
        return "Chat " + chat.get("id");
    }

    /** The chats Telegram's {@code getUpdates} result shows, once per id, the last seen entry winning. */
    public static List<Chat> discoverChats(Object updates) {
        if (!(updates instanceof List<?> list)) {
            return List.of();
        }
        Map<Long, Chat> found = new LinkedHashMap<>();
        for (Object update : list) {
            if (!(update instanceof Map<?, ?> fields)) {
                continue;
            }
            for (String kind : UPDATE_KINDS) {
                if (fields.get(kind) instanceof Map<?, ?> event && event.get("chat") instanceof Map<?, ?> chat
                        && number(chat.get("id")) != null) {
                    long id = number(chat.get("id"));
                    found.put(id, new Chat(id, chatTitle(chat), chatType(text(chat.get("type")))));
                }
            }
        }
        return List.copyOf(found.values());
    }

    /** Chats as stored in metadata: a list of {@code {id, title, type}} maps; anything else reads as empty. */
    public static List<Chat> chatsFromMetadata(Object stored) {
        if (!(stored instanceof List<?> list)) {
            return List.of();
        }
        List<Chat> chats = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> chat && number(chat.get("id")) != null) {
                long id = number(chat.get("id"));
                String title = text(chat.get("title"));
                chats.add(new Chat(id, title == null ? "Chat " + id : title, chatType(text(chat.get("type")))));
            }
        }
        return List.copyOf(chats);
    }

    /** Chats as the metadata and the API write them. */
    public static List<Map<String, Object>> chatMaps(List<Chat> chats) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Chat chat : chats) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", chat.id());
            map.put("title", chat.title());
            map.put("type", chat.type());
            maps.add(map);
        }
        return maps;
    }

    /** The default chat as stored: a number, or {@code null}. */
    public static Long defaultChatFromMetadata(Object stored) {
        return number(stored);
    }

    /** The given chat, else the default one; fails with {@code connector.telegram.noChat} when neither exists. */
    public static long resolveChat(Long given, Long defaultChat) {
        if (given != null) {
            return given;
        }
        if (defaultChat != null) {
            return defaultChat;
        }
        throw new ConnectorException(Reason.INVALID_INPUT, new LocalizedMessage("connector.telegram.noChat"));
    }

    public static boolean hasChat(List<Chat> chats, long id) {
        return chats.stream().anyMatch(chat -> chat.id() == id);
    }

    /** Text up to 4096 characters; blank only when a file goes with it. */
    public static void checkText(String text, boolean hasMedia) {
        boolean blank = text == null || text.isBlank();
        if ((blank && !hasMedia) || (text != null && text.length() > MAX_TEXT)) {
            throw new ConnectorException(Reason.INVALID_INPUT, new LocalizedMessage("connector.telegram.textInvalid"));
        }
    }

    /** An absolute {@code http} or {@code https} URL with a host. */
    public static void checkMediaUrl(String url) {
        if (!isHttpUrl(url)) {
            throw new ConnectorException(Reason.INVALID_INPUT,
                    new LocalizedMessage("connector.telegram.mediaUrlInvalid"));
        }
    }

    /** The Telegram method that carries the message. */
    public static String method(TelegramMedia media) {
        if (media == null) {
            return "sendMessage";
        }
        return switch (media.kind()) {
            case PHOTO -> "sendPhoto";
            case VIDEO -> "sendVideo";
            case DOCUMENT -> "sendDocument";
        };
    }

    /** The caption of a file message: the text cut at 1024 characters. */
    public static String caption(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_CAPTION ? text.substring(0, MAX_CAPTION) : text;
    }

    /** The {@code parse_mode} to send; HTML when none was asked for. */
    public static String parseMode(TelegramMessage.ParseMode mode) {
        return (mode == null ? TelegramMessage.ParseMode.HTML : mode).wire();
    }

    /** {@code <title> (<id>)} for a stored chat, the bare id otherwise. */
    public static String recipientLabel(List<Chat> chats, long id) {
        return chats.stream().filter(chat -> chat.id() == id).findFirst()
                .map(chat -> chat.title() + " (" + chat.id() + ")")
                .orElse(Long.toString(id));
    }

    /**
     * Maps a refused send to its reason; the first match wins, so a blocked bot beats a 401.
     *
     * @param code Telegram's {@code error_code}, or the HTTP status when the body had none
     * @param retryAfter Telegram's {@code parameters.retry_after}, if any
     */
    public static Refusal classifyRefusal(int code, String description, Integer retryAfter) {
        String text = description == null ? "" : description.toLowerCase(Locale.ROOT);
        if (text.contains("blocked")) {
            return refused("recipientBlocked", description);
        }
        if (text.contains("kicked") || text.contains("not a member")) {
            return refused("botNotInChat", description);
        }
        if (text.contains("chat not found")) {
            return refused("chatNotFound", description);
        }
        if (text.contains("too big") || code == 413) {
            return refused("fileTooLarge", description);
        }
        if (code == 401) {
            return refused("tokenInvalid", description);
        }
        if (code == 429) {
            return new Refusal(Refusal.Kind.RATE_LIMITED, "rateLimited", true, waitFor(text, retryAfter), description);
        }
        if (code >= 500) {
            return new Refusal(Refusal.Kind.UPSTREAM_UNAVAILABLE, "upstreamUnavailable", true, null, description);
        }
        return refused("rejected", description);
    }

    private static Refusal refused(String reason, String description) {
        return new Refusal(Refusal.Kind.REFUSED, reason, false, null, description);
    }

    private static Duration waitFor(String lowerDescription, Integer retryAfter) {
        if (retryAfter != null && retryAfter > 0) {
            return Duration.ofSeconds(retryAfter);
        }
        Matcher matcher = RETRY_AFTER.matcher(lowerDescription);
        if (matcher.find()) {
            try {
                long seconds = Long.parseLong(matcher.group(1));
                return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_RETRY_AFTER;
            } catch (NumberFormatException ex) {
                return DEFAULT_RETRY_AFTER;
            }
        }
        return DEFAULT_RETRY_AFTER;
    }

    private static boolean isHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (java.net.URISyntaxException ex) {
            return false;
        }
    }

    private static String chatType(String type) {
        if (type == null) {
            return "group";
        }
        String clean = type.trim().toLowerCase(Locale.ROOT);
        return CHAT_TYPES.contains(clean) ? clean : "group";
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
