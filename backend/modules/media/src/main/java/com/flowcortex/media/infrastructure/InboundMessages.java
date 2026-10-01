package com.flowcortex.media.infrastructure;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a worker message strictly: a field the contract does not name is an error, not ignored,
 * so a worker that drifted from the schema is caught at the first message. A message that cannot
 * be read is rejected without requeue and lands on its queue's DLQ.
 */
final class InboundMessages {

    private static final Logger LOG = LoggerFactory.getLogger(InboundMessages.class);
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private InboundMessages() {
    }

    static <T> T read(Message message, Class<T> type, String kind) {
        try {
            return STRICT.readValue(message.getBody(), type);
        } catch (JacksonException e) {
            throw reject(kind, jobIdOf(message.getBody()), e.getOriginalMessage());
        }
    }

    /** Logs one WARN naming the job when there is one, and returns the exception that dead-letters the message. */
    static AmqpRejectAndDontRequeueException reject(String kind, UUID jobId, String reason) {
        String oneLine = reason == null ? "unreadable" : reason.replaceAll("[\\r\\n]+", " ");
        LOG.warn("Invalid media {} for job {} moved to its dead-letter queue: {}", kind,
                jobId == null ? "<unknown>" : jobId, oneLine);
        return new AmqpRejectAndDontRequeueException("Invalid media " + kind + ": " + oneLine);
    }

    /** The {@code jobId} of a body that is not a valid message, when it holds one; never echoes anything else. */
    static UUID jobIdOf(byte[] body) {
        try {
            JsonNode jobId = STRICT.readTree(body).get("jobId");
            return jobId != null && jobId.isString() ? UUID.fromString(jobId.asString()) : null;
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }
}
