package com.flowcortex.chat.realtime;

import com.flowcortex.chat.turn.ChatEvents;
import com.flowcortex.realtime.api.RealtimeEvents;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes a turn's progress on {@code chat:<conversationId>} through the realtime hub. A delivery
 * problem is logged and never reaches the turn: the thread's reload repairs a missed event.
 */
@Component("chatRealtimeEvents")
class RealtimeChatEvents implements ChatEvents {

    static final String DELTA = "chat.delta";
    static final String UPDATED = "chat.updated";

    private static final Logger LOG = LoggerFactory.getLogger(RealtimeChatEvents.class);

    private final RealtimeEvents realtime;

    RealtimeChatEvents(RealtimeEvents realtime) {
        this.realtime = realtime;
    }

    @Override
    public void delta(UUID conversationId, UUID messageId, int offset, String text) {
        for (DeltaSplitter.Piece piece : DeltaSplitter.split(offset, text)) {
            publish(conversationId, DELTA, Map.of("messageId", messageId, "offset", piece.offset(),
                    "text", piece.text()));
        }
    }

    @Override
    public void updated(UUID conversationId, UUID messageId) {
        publish(conversationId, UPDATED, Map.of("messageId", messageId));
    }

    private void publish(UUID conversationId, String type, Map<String, Object> payload) {
        try {
            realtime.publish(ChatTopicAuthorizer.PREFIX + conversationId, type, payload);
        } catch (RuntimeException e) {
            LOG.warn("{} outcome=publish_failed conversation={}", type, conversationId, e);
        }
    }
}
