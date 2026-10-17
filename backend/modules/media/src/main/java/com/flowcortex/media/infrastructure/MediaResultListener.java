package com.flowcortex.media.infrastructure;

import com.flowcortex.media.api.MediaResult;
import com.flowcortex.media.application.MediaInbound;
import jakarta.validation.ConstraintViolationException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Results from {@code media.results}. The message is acknowledged only once {@link MediaInbound}
 * committed it; a failure to record it is retried by the container, then dead-lettered.
 */
@Component
class MediaResultListener {

    private final MediaInbound inbound;

    MediaResultListener(MediaInbound inbound) {
        this.inbound = inbound;
    }

    // Started by MediaTopologyCheck once the topology is declared.
    @RabbitListener(id = "mediaResults", queues = MediaTopologyConfig.RESULTS, autoStartup = "false")
    void onResult(Message message) {
        accept(message);
    }

    @RabbitListener(id = "providerResults", queues = MediaTopologyConfig.PROVIDER_RESULTS, autoStartup = "false")
    void onProviderResult(Message message) {
        accept(message);
    }

    private void accept(Message message) {
        MediaResult result = InboundMessages.read(message, MediaResult.class, "result");
        try {
            inbound.acceptResult(result);
        } catch (ConstraintViolationException e) {
            throw InboundMessages.reject("result", result.jobId(), e.getMessage());
        }
    }
}
