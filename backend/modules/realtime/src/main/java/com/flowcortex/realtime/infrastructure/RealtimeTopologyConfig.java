package com.flowcortex.realtime.infrastructure;

import com.flowcortex.realtime.application.RealtimeProperties;
import java.util.Map;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Base64UrlNamingStrategy;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The fanout exchange and this instance's own queue. They are plain beans: the context's single
 * {@code RabbitAdmin} declares them with the rest of the topology, and the listener container
 * redeclares the queue under the same name after a broker failure.
 */
@Configuration(proxyBeanMethods = false)
class RealtimeTopologyConfig {

    static final String EXCHANGE = "realtime.events";
    static final String LISTENER_ID = "realtimeEvents";

    @Bean
    FanoutExchange realtimeEventsExchange() {
        return new FanoutExchange(EXCHANGE, true, false);
    }

    /** Exclusive and auto-delete, so a stopped instance leaves nothing behind; drop-head caps a stalled consumer. */
    @Bean
    Queue realtimeEventsQueue(RealtimeProperties properties) {
        return new AnonymousQueue(new Base64UrlNamingStrategy("realtime."),
                Map.<String, Object>of("x-max-length", properties.queueMaxLength()));
    }

    @Bean
    Binding realtimeEventsBinding(Queue realtimeEventsQueue, FanoutExchange realtimeEventsExchange) {
        return BindingBuilder.bind(realtimeEventsQueue).to(realtimeEventsExchange);
    }
}
