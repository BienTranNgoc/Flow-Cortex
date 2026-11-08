package com.flowcortex.realtime.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.realtime.application.SseHub;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.listener.AsyncConsumerRestartedEvent;
import org.springframework.amqp.rabbit.listener.ConsumeOkEvent;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

class RealtimeConsumerRestartListenerTest {

    private final SseHub hub = mock(SseHub.class);
    private final RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
    private final MessageListenerContainer ours = mock(MessageListenerContainer.class);
    private final MessageListenerContainer media = mock(MessageListenerContainer.class);
    private final Queue queue = new AnonymousQueue();
    private final RealtimeConsumerRestartListener listener;

    RealtimeConsumerRestartListenerTest() {
        when(registry.getListenerContainer(RealtimeTopologyConfig.LISTENER_ID)).thenReturn(ours);
        listener = new RealtimeConsumerRestartListener(hub, registry, queue);
    }

    private void restarted(Object container) {
        listener.onApplicationEvent(new AsyncConsumerRestartedEvent(container, new Object(), new Object()));
    }

    private void consuming(String queueName) {
        listener.onApplicationEvent(new ConsumeOkEvent(ours, queueName, "tag"));
    }

    @Test
    void theFirstConsumeAfterStartupSendsNoResync() {
        consuming(queue.getName());

        verify(hub, never()).resync();
    }

    @Test
    void aRestartAloneSendsNothingUntilTheBrokerConfirmsTheConsumer() {
        restarted(ours);

        verify(hub, never()).resync();
    }

    @Test
    void severalFailedRestartsThenOneConfirmedConsumeSendOneResync() {
        restarted(ours);
        restarted(ours);
        restarted(ours);
        consuming(queue.getName());
        consuming(queue.getName());

        verify(hub, times(1)).resync();
    }

    @Test
    void anotherContainersRestartAndAnotherQueuesConsumeAreIgnored() {
        restarted(media);
        consuming(queue.getName());
        restarted(ours);
        consuming("media.results");

        verify(hub, never()).resync();
    }

    @Test
    void aSecondOutageSendsASecondResync() {
        restarted(ours);
        consuming(queue.getName());
        restarted(ours);
        consuming(queue.getName());

        verify(hub, times(2)).resync();
    }
}
