package com.flowcortex.chat.web;

import com.flowcortex.chat.conversation.ConversationStore.StoredMessage;
import com.flowcortex.chat.web.ConversationController.MessageView;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Renders a stored message the way every chat route returns it. */
@Component("chatMessageViews")
class MessageViews {

    private final JsonMapper jsonMapper;

    MessageViews(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    MessageView view(StoredMessage message) {
        return new MessageView(message.id(), message.seq(), message.role(), message.kind(), message.body(),
                payload(message.payload()), message.status(), message.createdAt());
    }

    private JsonNode payload(String json) {
        if (json == null) {
            return null;
        }
        try {
            return jsonMapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("chat_message.payload is not JSON", e);
        }
    }
}
