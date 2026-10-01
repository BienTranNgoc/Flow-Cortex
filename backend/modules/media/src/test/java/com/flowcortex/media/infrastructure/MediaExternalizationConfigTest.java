package com.flowcortex.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;

/** The ids a job message leaves with. */
class MediaExternalizationConfigTest {

    private final MessagePostProcessor ids = new MediaExternalizationConfig.JobMessageIds();
    private final UUID jobId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    private Message job(String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        String body = "{\"jobId\":\"" + jobId + "\",\"runId\":\"" + runId + "\"}";
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    @Test
    void aJobMessageGetsTheJobIdAsMessageIdAndTheRunIdAsCorrelationId() {
        MessageProperties sent = ids.postProcessMessage(job(null), null, "media", "job").getMessageProperties();

        assertThat(sent.getMessageId()).isEqualTo(jobId.toString());
        assertThat(sent.getCorrelationId()).isEqualTo(runId.toString());
    }

    @Test
    void idsTheMessageAlreadyCarriesAreKept() {
        MessageProperties sent = ids.postProcessMessage(job("set-upstream"), null, "media", "job")
                .getMessageProperties();

        assertThat(sent.getMessageId()).isEqualTo("set-upstream");
        assertThat(sent.getCorrelationId()).isNull();
    }

    @Test
    void aMessageOnAnotherRouteIsLeftAlone() {
        MessageProperties sent = ids.postProcessMessage(job(null), null, "media", "result").getMessageProperties();

        assertThat(sent.getMessageId()).isNull();
        assertThat(sent.getCorrelationId()).isNull();
        assertThat(sent.getHeaders()).doesNotContainKey("baggage");
    }

    @Test
    void aJobMessageSentWithoutBaggageCarriesTheRunIdAsBaggage() {
        MessageProperties sent = ids.postProcessMessage(job(null), null, "media", "job").getMessageProperties();

        assertThat(sent.getHeaders()).containsEntry("baggage", "runId=" + runId);
    }

    @Test
    void baggageTheMessageAlreadyCarriesIsKept() {
        Message job = job(null);
        job.getMessageProperties().setHeader("baggage", "runId=set-upstream");

        MessageProperties sent = ids.postProcessMessage(job, null, "media", "job").getMessageProperties();

        assertThat(sent.getHeaders()).containsEntry("baggage", "runId=set-upstream");
    }
}
