package com.flowcortex.media.infrastructure;

import com.flowcortex.media.api.MediaProgress;
import com.flowcortex.media.application.MediaInbound;
import jakarta.validation.ConstraintViolationException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Pickups and heartbeats from {@code media.progress}, handled like results. */
@Component
class MediaProgressListener {

    private final MediaInbound inbound;

    MediaProgressListener(MediaInbound inbound) {
        this.inbound = inbound;
    }

    // Started by MediaTopologyCheck once the topology is declared.
    @RabbitListener(id = "mediaProgress", queues = MediaTopologyConfig.PROGRESS, autoStartup = "false")
    void onProgress(Message message) {
        MediaProgress progress = InboundMessages.read(message, MediaProgress.class, "progress");
        try {
            inbound.acceptProgress(progress);
        } catch (ConstraintViolationException e) {
            throw InboundMessages.reject("progress", progress.jobId(), e.getMessage());
        }
    }
}
