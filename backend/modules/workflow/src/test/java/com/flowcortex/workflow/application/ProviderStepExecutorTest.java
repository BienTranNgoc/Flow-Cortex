package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.ProviderJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProviderStepExecutorTest {

    private final MediaJobs jobs = mock(MediaJobs.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void scriptJobCarriesOnlyConfirmedProductPayload() {
        ProviderStepExecutor executor = executor("generate_script", "product_ad_v1");
        StepContext context = context("""
                {"productName":"Bình giữ nhiệt","description":"Giữ đồ uống","cta":"Mua ngay"}
                """, "{\"profile\":\"product_ad_v1\"}");

        executor.execute(context);

        ArgumentCaptor<ProviderJob> sent = ArgumentCaptor.forClass(ProviderJob.class);
        verify(jobs).enqueueProvider(sent.capture());
        assertThat(sent.getValue().capability()).isEqualTo("generate_script");
        assertThat(sent.getValue().params()).containsEntry("profile", "product_ad_v1");
        assertThat(castPayload(sent.getValue())).containsOnlyKeys("productName", "description", "cta");
    }

    @Test
    void speechJobWrapsApprovedScript() {
        ProviderStepExecutor executor = executor("generate_speech", "vi_voice_v1");
        executor.execute(context("Kịch bản đã duyệt", "{\"profile\":\"vi_voice_v1\"}"));

        ArgumentCaptor<ProviderJob> sent = ArgumentCaptor.forClass(ProviderJob.class);
        verify(jobs).enqueueProvider(sent.capture());
        assertThat(castPayload(sent.getValue())).containsEntry("script", "Kịch bản đã duyệt");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> castPayload(ProviderJob job) {
        return (java.util.Map<String, Object>) job.params().get("payload");
    }

    private ProviderStepExecutor executor(String capability, String profile) {
        return new ProviderStepExecutor(capability, profile, jobs, clock, Duration.ofMinutes(2),
                Duration.ofMinutes(5));
    }

    private static StepContext context(String input, String config) {
        UUID runId = UUID.randomUUID();
        return new StepContext(runId, UUID.randomUUID(), 1, "provider", (short) 1, input, config);
    }
}
