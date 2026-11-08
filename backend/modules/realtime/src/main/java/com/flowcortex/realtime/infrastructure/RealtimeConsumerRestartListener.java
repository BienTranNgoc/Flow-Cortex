package com.flowcortex.realtime.infrastructure;

import com.flowcortex.realtime.application.SseHub;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.event.AmqpEvent;
import org.springframework.amqp.rabbit.listener.AsyncConsumerRestartedEvent;
import org.springframework.amqp.rabbit.listener.ConsumeOkEvent;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * After a consumer restart every open stream is told to resync once. The restarted event fires
 * per attempt, failed ones included, so it only arms the signal and the broker's consume
 * confirmation for this module's queue sends it.
 */
@Component
class RealtimeConsumerRestartListener implements ApplicationListener<AmqpEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(RealtimeConsumerRestartListener.class);

    private final SseHub hub;
    private final RabbitListenerEndpointRegistry listeners;
    private final Queue queue;
    private final AtomicBoolean resyncOnConsume = new AtomicBoolean();

    RealtimeConsumerRestartListener(SseHub hub, RabbitListenerEndpointRegistry listeners, Queue realtimeEventsQueue) {
        this.hub = hub;
        this.listeners = listeners;
        this.queue = realtimeEventsQueue;
    }

    @Override
    public void onApplicationEvent(AmqpEvent event) {
        if (event instanceof AsyncConsumerRestartedEvent restarted
                && restarted.getSource() == listeners.getListenerContainer(RealtimeTopologyConfig.LISTENER_ID)) {
            resyncOnConsume.set(true);
        } else if (event instanceof ConsumeOkEvent consuming && queue.getName().equals(consuming.getQueue())
                && resyncOnConsume.compareAndSet(true, false)) {
            LOG.info("The realtime consumer is back on {}; telling open streams to resync", queue.getName());
            hub.resync();
        }
    }
}
