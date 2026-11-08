package com.flowcortex.realtime.infrastructure;

import com.flowcortex.realtime.application.SseHub;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hands each event from this instance's queue to the local hub. A message it cannot read is logged
 * and dropped: it is a signal, and retrying or dead-lettering one would only repeat the failure.
 */
@Component
class RealtimeEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(RealtimeEventListener.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final SseHub hub;

    RealtimeEventListener(SseHub hub) {
        this.hub = hub;
    }

    // Started with the other listeners once the topology is declared.
    @RabbitListener(id = RealtimeTopologyConfig.LISTENER_ID, queues = "#{realtimeEventsQueue.name}", autoStartup = "false")
    void onMessage(Message message) {
        JsonNode root;
        try {
            root = MAPPER.readTree(message.getBody());
        } catch (JacksonException e) {
            LOG.warn("Dropped an unreadable realtime message: {}", e.getOriginalMessage());
            return;
        }
        JsonNode topic = root.path("topic");
        JsonNode type = root.path("type");
        if (!topic.isString() || !type.isString() || !root.has("data")) {
            LOG.warn("Dropped a realtime message without topic, type and data: {}",
                    new String(message.getBody(), 0, Math.min(message.getBody().length, 200), StandardCharsets.UTF_8));
            return;
        }
        hub.onEvent(topic.stringValue(), type.stringValue(), root.get("data").toString());
    }
}
