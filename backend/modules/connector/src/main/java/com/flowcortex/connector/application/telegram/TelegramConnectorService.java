package com.flowcortex.connector.application.telegram;

import com.flowcortex.connector.api.ConnectorConnection;
import com.flowcortex.connector.api.ConnectorCredentials;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.ConnectorTokens;
import com.flowcortex.connector.api.TelegramConnector;
import com.flowcortex.connector.api.TelegramDelivery;
import com.flowcortex.connector.api.TelegramMedia;
import com.flowcortex.connector.api.TelegramMessage;
import com.flowcortex.connector.application.ConnectorAccess;
import com.flowcortex.connector.application.ConnectorAccess.Granted;
import com.flowcortex.connector.application.telegram.TelegramBotApi.Reply;
import com.flowcortex.connector.application.telegram.TelegramRules.Chat;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Telegram connector: connect, read, refresh chats, default chat, health and the runtime send. Every
 * operation passes through {@link ConnectorAccess} and the Telegram switch; the bot token is read only
 * there, handed to {@link TelegramBotApi} and never returned or logged.
 */
@Service
public class TelegramConnectorService implements TelegramConnector {

    static final String PROVIDER = "telegram";
    static final String CONNECTED = "connected";
    static final String AWAITING_CHAT = "awaiting_chat";

    private static final Logger LOG = LoggerFactory.getLogger(TelegramConnectorService.class);

    private final ConnectorAccess access;
    private final ConnectorCredentials credentials;
    private final TelegramBotApi api;
    private final TelegramProperties properties;

    TelegramConnectorService(ConnectorAccess access, ConnectorCredentials credentials, TelegramBotApi api,
            TelegramProperties properties) {
        this.access = access;
        this.credentials = credentials;
        this.api = api;
        this.properties = properties;
    }

    /** Confirms the token with Telegram, discovers chats, and stores the bot, replacing any earlier one. */
    public TelegramConnection connect(UUID memberId, String rawToken) {
        access.requireMember(memberId, properties.enabled());
        String token = TelegramRules.normalizeToken(rawToken)
                .orElseThrow(() -> invalid(new LocalizedMessage("connector.telegram.tokenFormat")));
        Reply me = api.getMe(token);
        if (me.tokenRejected()) {
            throw invalid(new LocalizedMessage("connector.telegram.tokenInvalid"));
        }
        if (!me.ok() || !(me.result() instanceof Map<?, ?> bot) || !(bot.get("id") instanceof Number id)
                || !(bot.get("username") instanceof String username)) {
            throw ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE);
        }
        List<Chat> chats = discoverOrEmpty(token);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("botUsername", username);
        metadata.put("botId", id.longValue());
        metadata.put("chats", TelegramRules.chatMaps(chats));
        metadata.put("defaultChatId", null);
        credentials.connect(memberId, PROVIDER, new ConnectorTokens(token, null, null, List.of()), metadata);
        return new TelegramConnection(PROVIDER, username, id.longValue(), AWAITING_CHAT, chats, null, true);
    }

    /** The stored connection: no usage check, no Telegram call, no token read. */
    public TelegramConnection read(UUID memberId) {
        return view(access.manage(memberId, PROVIDER, properties.enabled(), false).connection());
    }

    /** Adds the chat entered by id, or the chats Telegram shows now; never removes a stored chat. */
    public TelegramChatList refreshChats(UUID memberId, Long chatId, String title, String type) {
        Granted granted = access.use(memberId, PROVIDER, properties.enabled());
        List<Chat> stored = TelegramRules.chatsFromMetadata(granted.connection().metadata().get("chats"));
        List<Chat> incoming = chatId != null
                ? List.of(TelegramRules.manualChat(chatId, title, type))
                : discover(granted.tokens().accessToken());
        List<Chat> merged = TelegramRules.mergeChats(stored, incoming);
        if (!credentials.mergeMetadata(memberId, PROVIDER, Map.of("chats", TelegramRules.chatMaps(merged)), null)) {
            throw ConnectorException.of(Reason.NOT_CONNECTED);
        }
        return new TelegramChatList(merged);
    }

    /** Sets the default chat to a stored one; the store re-checks membership in the same write. */
    public TelegramDefaultChat setDefaultChat(UUID memberId, long chatId) {
        Granted granted = access.manage(memberId, PROVIDER, properties.enabled(), false);
        List<Chat> stored = TelegramRules.chatsFromMetadata(granted.connection().metadata().get("chats"));
        if (!TelegramRules.hasChat(stored, chatId)
                || !credentials.mergeMetadata(memberId, PROVIDER, Map.of("defaultChatId", chatId),
                        Map.of("chats", List.of(Map.of("id", chatId))))) {
            throw invalid(new LocalizedMessage("connector.telegram.chatNotFound"));
        }
        return new TelegramDefaultChat(CONNECTED, chatId);
    }

    /** Asks Telegram whether the stored token still works. */
    public TelegramHealth health(UUID memberId) {
        Granted granted = access.manage(memberId, PROVIDER, properties.enabled(), true);
        Reply me = api.getMe(granted.tokens().accessToken());
        if (me.tokenRejected()) {
            return new TelegramHealth(false, PROVIDER, null, "tokenInvalid");
        }
        if (!me.ok()) {
            throw ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE);
        }
        String username = me.result() instanceof Map<?, ?> bot && bot.get("username") instanceof String name
                ? name : view(granted.connection()).botUsername();
        return new TelegramHealth(true, PROVIDER, username, null);
    }

    @Override
    public TelegramDelivery send(UUID memberId, TelegramMessage message) {
        Granted granted = access.use(memberId, PROVIDER, properties.enabled());
        List<Chat> chats = TelegramRules.chatsFromMetadata(granted.connection().metadata().get("chats"));
        long chatId = TelegramRules.resolveChat(message.chatId(),
                TelegramRules.defaultChatFromMetadata(granted.connection().metadata().get("defaultChatId")));
        TelegramMedia media = message.media();
        TelegramRules.checkText(message.text(), media != null);
        if (media != null) {
            TelegramRules.checkMediaUrl(media.kind() == null ? null : media.url());
        }
        Reply reply = api.send(granted.tokens().accessToken(), TelegramRules.method(media), body(chatId, message));
        if (!reply.ok()) {
            throw TelegramRules.classifyRefusal(reply.errorCode(), reply.description(), reply.retryAfter())
                    .exception();
        }
        if (!(reply.result() instanceof Map<?, ?> sent) || !(sent.get("message_id") instanceof Number messageId)) {
            // Telegram said ok but gave no message id: it may have posted, so a retry could duplicate.
            throw new ConnectorException(Reason.UPSTREAM_UNAVAILABLE, false,
                    new LocalizedMessage("connector.upstreamUnavailable"), null, null);
        }
        return new TelegramDelivery(messageId.longValue(), TelegramRules.recipientLabel(chats, chatId));
    }

    private static Map<String, Object> body(long chatId, TelegramMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        TelegramMedia media = message.media();
        if (media == null) {
            body.put("text", message.text());
        } else {
            body.put(switch (media.kind()) {
                case PHOTO -> "photo";
                case VIDEO -> "video";
                case DOCUMENT -> "document";
            }, media.url().trim());
            String caption = TelegramRules.caption(message.text());
            if (!caption.isBlank()) {
                body.put("caption", caption);
            }
        }
        body.put("parse_mode", TelegramRules.parseMode(message.parseMode()));
        return body;
    }

    /** Discovery for a refresh: a rejected token is the member's to fix; any other refusal finds no chats. */
    private List<Chat> discover(String token) {
        Reply updates = api.getUpdates(token);
        if (updates.tokenRejected()) {
            throw invalid(new LocalizedMessage("connector.telegram.tokenInvalid"));
        }
        return updates.ok() ? TelegramRules.discoverChats(updates.result()) : List.of();
    }

    /** Discovery for a connect: the token is already confirmed, so any failure only leaves the list empty. */
    private List<Chat> discoverOrEmpty(String token) {
        try {
            Reply updates = api.getUpdates(token);
            return updates.ok() ? TelegramRules.discoverChats(updates.result()) : List.of();
        } catch (ConnectorException ex) {
            LOG.warn("Telegram chat discovery failed on connect ({}); storing no chats", ex.reason());
            return List.of();
        }
    }

    private static TelegramConnection view(ConnectorConnection connection) {
        Map<String, Object> metadata = connection.metadata();
        Long defaultChatId = TelegramRules.defaultChatFromMetadata(metadata.get("defaultChatId"));
        Object botId = metadata.get("botId");
        return new TelegramConnection(PROVIDER,
                metadata.get("botUsername") instanceof String name ? name : null,
                botId instanceof Number number ? number.longValue() : 0L,
                defaultChatId == null ? AWAITING_CHAT : CONNECTED,
                TelegramRules.chatsFromMetadata(metadata.get("chats")), defaultChatId, connection.usageEnabled());
    }

    private static ConnectorException invalid(LocalizedMessage detail) {
        return new ConnectorException(Reason.INVALID_INPUT, detail);
    }
}
