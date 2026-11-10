package com.flowcortex.chat.realtime;

import com.flowcortex.chat.conversation.ConversationStore;
import com.flowcortex.realtime.api.TopicAuthorizer;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Claims {@code chat:<uuid>} topics; only the conversation's owner may read one. */
@Component("chatTopicAuthorizer")
class ChatTopicAuthorizer implements TopicAuthorizer {

    static final String PREFIX = "chat:";

    private final ConversationStore conversations;

    ChatTopicAuthorizer(ConversationStore conversations) {
        this.conversations = conversations;
    }

    /** Only the canonical lowercase form is claimed: it is the form the publisher builds the topic from. */
    @Override
    public boolean supports(String topic) {
        return conversationId(topic) != null;
    }

    @Override
    public boolean authorize(UUID userId, String topic) {
        UUID conversationId = conversationId(topic);
        return conversationId != null && conversations.find(userId, conversationId).isPresent();
    }

    private static UUID conversationId(String topic) {
        if (topic == null || !topic.startsWith(PREFIX)) {
            return null;
        }
        String id = topic.substring(PREFIX.length());
        try {
            UUID parsed = UUID.fromString(id);
            return parsed.toString().equals(id) ? parsed : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
