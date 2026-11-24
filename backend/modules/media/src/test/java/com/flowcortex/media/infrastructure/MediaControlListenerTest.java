package com.flowcortex.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaControlResponse;
import com.flowcortex.media.api.MediaJobControl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MediaControlListenerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final MediaJobControl jobControl = mock(MediaJobControl.class);
    private final RabbitOperations template = mock(RabbitOperations.class);
    private final MediaControlListener listener =
            new MediaControlListener(jobControl, validation.getValidator(), template);

    private final UUID runId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @AfterEach
    void closeValidation() {
        validation.close();
    }

    private static Message message(String body, String replyTo, String correlationId) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (replyTo != null) {
            properties.setReplyTo(replyTo);
        }
        if (correlationId != null) {
            properties.setCorrelationId(correlationId);
        }
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private String validBody(int attempt) {
        return "{\"schemaVersion\":\"1\",\"runId\":\"" + runId + "\",\"jobId\":\"" + jobId
                + "\",\"attempt\":" + attempt + "}";
    }

    @Test
    void repliesWithControlDecisionToScopedReplyQueue() {
        when(jobControl.decide(runId, jobId, 2)).thenReturn(MediaControlResponse.Decision.CANCEL_SIBLING_FAILED);
        String replyTo = "provider.control.reply.video-worker-1";

        listener.onControlRequest(message(validBody(2), replyTo, "corr-42"));

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(""), eq(replyTo), sent.capture());
        Message reply = sent.getValue();
        assertThat(reply.getMessageProperties().getCorrelationId()).isEqualTo("corr-42");
        assertThat(reply.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.NON_PERSISTENT);
        JsonNode body = JSON.readTree(reply.getBody());
        assertThat(body.get("schemaVersion").asString()).isEqualTo("1");
        assertThat(body.get("runId").asString()).isEqualTo(runId.toString());
        assertThat(body.get("jobId").asString()).isEqualTo(jobId.toString());
        assertThat(body.get("attempt").asInt()).isEqualTo(2);
        assertThat(body.get("decision").asString()).isEqualTo("cancel_sibling_failed");
    }

    @Test
    void rejectsUnknownFieldOrInvalidReplyTargetWithoutCallingPort() {
        String unknownField = "{\"schemaVersion\":\"1\",\"runId\":\"" + runId + "\",\"jobId\":\"" + jobId
                + "\",\"attempt\":1,\"extra\":true}";
        assertThatThrownBy(() -> listener.onControlRequest(message(unknownField, "provider.control.reply.w1", "c1")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.onControlRequest(message(validBody(0), "provider.control.reply.w1", "c1")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.onControlRequest(message(validBody(1), "provider.results", "c1")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.onControlRequest(message(validBody(1), "provider.control.reply.", "c1")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.onControlRequest(message(validBody(1), "provider.control.reply.w1", null)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(jobControl, never()).decide(any(), any(), anyInt());
        verify(template, never()).send(anyString(), anyString(), any(Message.class));
    }
}
