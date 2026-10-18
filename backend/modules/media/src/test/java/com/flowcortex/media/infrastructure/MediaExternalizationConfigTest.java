package com.flowcortex.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.media.api.ProviderJob;
import com.flowcortex.media.application.ProviderJobRequested;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/** Where a job message goes and the ids it leaves with. */
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
    void aProviderJobGetsTheSameTraceableIds() {
        MessageProperties sent = ids.postProcessMessage(job(null), null, "provider", "job")
                .getMessageProperties();

        assertThat(sent.getMessageId()).isEqualTo(jobId.toString());
        assertThat(sent.getCorrelationId()).isEqualTo(runId.toString());
        assertThat(sent.getHeaders()).containsEntry("baggage", "runId=" + runId);
    }

    @Test
    void aVideoJobGetsTheSameTraceableIds() {
        MessageProperties sent = ids.postProcessMessage(job(null), null, "provider", "video.job")
                .getMessageProperties();

        assertThat(sent.getMessageId()).isEqualTo(jobId.toString());
        assertThat(sent.getCorrelationId()).isEqualTo(runId.toString());
        assertThat(sent.getHeaders()).containsEntry("baggage", "runId=" + runId);
    }

    @Test
    void videoClipsLeaveOnTheVideoKeyAndOtherProviderJobsOnTheJobKey() {
        EventExternalizationConfiguration configuration = new MediaExternalizationConfig().mediaExternalization();

        for (String capability : List.of("generate_video", "generate_script", "generate_storyboard",
                "generate_speech")) {
            ProviderJob job = providerJob(capability);
            RoutingTarget target = configuration.determineTarget(new ProviderJobRequested(job));

            assertThat(target.getTarget()).as(capability).isEqualTo("provider");
            assertThat(target.getKey()).as(capability)
                    .isEqualTo("generate_video".equals(capability) ? "video.job" : "job");
            assertThat(configuration.map(new ProviderJobRequested(job))).as(capability).isSameAs(job);
        }
    }

    private ProviderJob providerJob(String capability) {
        return new ProviderJob("1", jobId, runId, runId, 1, capability, Map.of(), List.of(),
                "runs/" + runId + "/steps/1/attempts/1/", 1, 60, Instant.now());
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
