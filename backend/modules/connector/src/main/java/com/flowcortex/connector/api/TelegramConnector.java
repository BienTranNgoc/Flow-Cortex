package com.flowcortex.connector.api;

import java.util.UUID;

/** Server-side access to a member's Telegram bot, for workflow steps and other modules. */
public interface TelegramConnector {

    /**
     * Posts the message through the member's bot. {@code memberId} is the owner of the run, never a
     * caller-chosen id: the member's own token is used and nobody else's.
     *
     * @throws ConnectorException for every failure, with a reason and whether a retry may succeed; a request
     *         is never sent twice, and no token reaches the exception
     */
    TelegramDelivery send(UUID memberId, TelegramMessage message);
}
