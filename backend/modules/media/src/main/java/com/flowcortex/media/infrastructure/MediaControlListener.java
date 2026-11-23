package com.flowcortex.media.infrastructure;

import com.flowcortex.media.api.MediaControlRequest;
import com.flowcortex.media.api.MediaControlResponse;
import com.flowcortex.media.api.MediaJobControl;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers video-worker control checks from {@code provider.control} on the request's scoped
 * {@code replyTo} queue.
 */
@Component
class MediaControlListener {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectProvider<MediaJobControl> jobControl;
    private final Validator validator;
    private final RabbitOperations template;

    @Autowired
    MediaControlListener(ObjectProvider<MediaJobControl> jobControl, Validator validator,
            RabbitOperations template) {
        this.jobControl = jobControl;
        this.validator = validator;
        this.template = template;
    }

    MediaControlListener(MediaJobControl jobControl, Validator validator, RabbitOperations template) {
        this(fixedProvider(jobControl), validator, template);
    }

    private static ObjectProvider<MediaJobControl> fixedProvider(MediaJobControl jobControl) {
        return new ObjectProvider<>() {
            @Override
            public MediaJobControl getObject() {
                return jobControl;
            }

            @Override
            public MediaJobControl getIfAvailable() {
                return jobControl;
            }
        };
    }

    // Started by MediaTopologyCheck once the topology is declared.
    @RabbitListener(id = "providerControl", queues = MediaTopologyConfig.PROVIDER_CONTROL, autoStartup = "false")
    void onControlRequest(Message message) {
        MediaControlRequest request = InboundMessages.read(message, MediaControlRequest.class, "control");
        Set<ConstraintViolation<MediaControlRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String reason = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw InboundMessages.reject("control", request.jobId(), reason);
        }
        MessageProperties inbound = message.getMessageProperties();
        String replyTo = inbound == null ? null : inbound.getReplyTo();
        String correlationId = inbound == null ? null : inbound.getCorrelationId();
        if (replyTo == null || !replyTo.startsWith(MediaTopologyConfig.CONTROL_REPLY_PREFIX)
                || replyTo.length() <= MediaTopologyConfig.CONTROL_REPLY_PREFIX.length()) {
            throw InboundMessages.reject("control", request.jobId(),
                    "replyTo must stay inside " + MediaTopologyConfig.CONTROL_REPLY_PREFIX);
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw InboundMessages.reject("control", request.jobId(), "correlationId is required");
        }
        MediaJobControl port = jobControl.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException("MediaJobControl bean is required to answer control checks");
        }
        MediaControlResponse.Decision decision = port.decide(request.runId(), request.jobId(), request.attempt());
        MediaControlResponse response = new MediaControlResponse("1", request.runId(), request.jobId(),
                request.attempt(), decision);
        byte[] body = JSON.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        Message reply = MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setCorrelationId(correlationId)
                .setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT)
                .build();
        template.send("", replyTo, reply);
    }
}
