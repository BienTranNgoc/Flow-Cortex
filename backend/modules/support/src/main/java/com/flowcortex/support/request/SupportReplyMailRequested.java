package com.flowcortex.support.request;

import java.util.UUID;

/**
 * A request's reply mail came due and its member is to be mailed. Published by the reply-mail sweep in the
 * transaction that clears the request's due marker, so the event registry records it exactly when the marker is
 * cleared and the mail goes out only once that is committed.
 *
 * @param locale the language the member filed the request in, kept here so the listener reads no request row
 */
public record SupportReplyMailRequested(UUID requestId, UUID messageId, UUID recipientUserId, long requestNumber,
        String locale) {
}
