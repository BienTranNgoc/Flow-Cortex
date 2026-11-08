package com.flowcortex.realtime.infrastructure;

import com.flowcortex.realtime.api.RealtimeEvents;
import com.flowcortex.realtime.application.RealtimeProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes from a thread of its own so a hung broker never holds the committing thread. The
 * executor is a field, not a bean: a bean of that type would switch Boot's own task executor off.
 */
@Component
class RabbitRealtimeBus implements RealtimeEvents {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitRealtimeBus.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RabbitOperations template;
    private final ThreadPoolExecutor executor;

    @Autowired
    RabbitRealtimeBus(RabbitOperations template, RealtimeProperties properties) {
        this(template, properties.publish().queueCapacity());
    }

    RabbitRealtimeBus(RabbitOperations template, int queueCapacity) {
        this.template = template;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), task -> {
            Thread thread = new Thread(task, "realtime-publish");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void publish(String topic, String type, Object payload) {
        Message message;
        try {
            message = message(topic, type, payload);
        } catch (RuntimeException e) {
            LOG.warn("Realtime event of type {} for topic {} could not be serialized and was dropped", type, topic, e);
            return;
        }
        try {
            executor.execute(() -> send(topic, type, message));
        } catch (RejectedExecutionException full) {
            LOG.warn("Realtime publish queue is full or closed; dropped the {} event for topic {}", type, topic);
        }
    }

    private static Message message(String topic, String type, Object payload) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("topic", topic);
        root.put("type", type);
        root.set("data", MAPPER.valueToTree(payload));
        return MessageBuilder.withBody(MAPPER.writeValueAsBytes(root))
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT)
                .build();
    }

    private void send(String topic, String type, Message message) {
        try {
            template.send(RealtimeTopologyConfig.EXCHANGE, "", message);
        } catch (RuntimeException e) {
            LOG.warn("Realtime {} event for topic {} could not be sent to the broker: {}", type, topic, e.toString());
        }
    }

    @PreDestroy
    void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
