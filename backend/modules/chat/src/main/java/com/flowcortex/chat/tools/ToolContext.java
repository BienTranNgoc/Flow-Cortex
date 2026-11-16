package com.flowcortex.chat.tools;

import com.flowcortex.chat.cards.Attachment;
import java.util.List;
import java.util.UUID;

/** Who a tool call acts for: the member, the conversation, and the images attached to the message being answered. */
public record ToolContext(UUID ownerId, UUID conversationId, List<Attachment> attachments) {

    public ToolContext {
        attachments = List.copyOf(attachments);
    }
}
