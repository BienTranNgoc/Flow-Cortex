package com.flowcortex.support.request;

import java.util.UUID;

/**
 * An admin reply was saved and its member is to be mailed. Published in the reply's transaction, so the
 * event registry records it with the reply and the mail goes out only once the reply is committed.
 *
 * @param locale the language the member filed the request in, kept here so the listener reads no request row
 */
public record SupportReplyMailRequested(UUID requestId, UUID messageId, UUID recipientUserId, long requestNumber,
        String locale) {
}
