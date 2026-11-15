package com.flowcortex.connector.application.telegram;

import java.util.Map;

/**
 * Telegram's Bot API as the connector uses it. Every method returns what Telegram answered, a refusal
 * included; only a failure to reach Telegram, or a 5xx on a lookup, is thrown.
 */
public interface TelegramBotApi {

    /**
     * Telegram's answer to one request.
     *
     * @param ok Telegram's {@code ok}
     * @param errorCode Telegram's {@code error_code}, else the HTTP status; {@code 0} when {@code ok}
     * @param description Telegram's {@code description}, may be {@code null}
     * @param retryAfter Telegram's {@code parameters.retry_after}, may be {@code null}
     * @param result Telegram's {@code result} as plain maps, lists and numbers, may be {@code null}
     */
    record Reply(boolean ok, int errorCode, String description, Integer retryAfter, Object result) {

        /** Telegram no longer accepts the token. */
        public boolean tokenRejected() {
            return !ok && (errorCode == 401 || errorCode == 404);
        }
    }

    /**
     * {@code getMe}.
     *
     * @throws com.flowcortex.connector.api.ConnectorException {@code UPSTREAM_UNAVAILABLE} when Telegram
     *         cannot be reached or answers 5xx
     */
    Reply getMe(String token);

    /**
     * {@code getUpdates} with {@code limit=100&timeout=0}.
     *
     * @throws com.flowcortex.connector.api.ConnectorException {@code UPSTREAM_UNAVAILABLE} when Telegram
     *         cannot be reached or answers 5xx
     */
    Reply getUpdates(String token);

    /**
     * A send method with its JSON body; a 4xx or 5xx answer comes back as a {@link Reply} so the caller can classify it.
     *
     * @throws com.flowcortex.connector.api.ConnectorException {@code UPSTREAM_UNAVAILABLE} when Telegram
     *         cannot be reached
     */
    Reply send(String token, String method, Map<String, Object> body);
}
