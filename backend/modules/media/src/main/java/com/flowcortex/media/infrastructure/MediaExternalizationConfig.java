package com.flowcortex.media.infrastructure;

import com.flowcortex.media.application.MediaJobRequested;
import org.springframework.amqp.core.Correlation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns each recorded {@link MediaJobRequested} into a job message: the body is the bare job, so
 * the worker reads exactly the job schema, and the message and correlation ids are the job and
 * run ids.
 */
@Configuration(proxyBeanMethods = false)
class MediaExternalizationConfig {

    @Bean
    EventExternalizationConfiguration mediaExternalization() {
        return EventExternalizationConfiguration.externalizing()
                .select(EventExternalizationConfiguration.annotatedAsExternalized())
                .mapping(MediaJobRequested.class, MediaJobRequested::job)
                .build();
    }

    /**
     * Spring Modulith 2.1.0 and 2.1.1 compute the headers an externalization configuration
     * declares, then send without them, so the ids are set on the way out, from the job body,
     * unless the message already carries one.
     */
    @Bean
    RabbitTemplateCustomizer jobMessageIds() {
        return template -> template.addBeforePublishPostProcessors(new JobMessageIds());
    }

    static final class JobMessageIds implements MessagePostProcessor {

        private static final JsonMapper JSON = JsonMapper.builder().build();

        @Override
        public Message postProcessMessage(Message message) {
            return message;
        }

        @Override
        public Message postProcessMessage(Message message, Correlation correlation, String exchange,
                String routingKey) {
            MessageProperties properties = message.getMessageProperties();
            if (MediaTopologyConfig.EXCHANGE.equals(exchange) && MediaTopologyConfig.JOB_KEY.equals(routingKey)
                    && properties.getMessageId() == null) {
                JsonNode job = JSON.readTree(message.getBody());
                properties.setMessageId(job.get("jobId").asString());
                properties.setCorrelationId(job.get("runId").asString());
            }
            return message;
        }
    }
}
