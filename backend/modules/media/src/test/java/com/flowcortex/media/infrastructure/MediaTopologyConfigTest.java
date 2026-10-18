package com.flowcortex.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.flowcortex.media.application.MediaProperties;
import com.flowcortex.media.api.MediaCapability;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

class MediaTopologyConfigTest {

    @Test
    void declaresIndependentProviderQueuesAndDeadLetterExchange() {
        Map<String, Duration> runtimes = Arrays.stream(MediaCapability.values())
                .collect(Collectors.toMap(MediaCapability::value, ignored -> Duration.ofMinutes(5)));
        MediaProperties properties = new MediaProperties(
                true,
                Duration.ofMinutes(30),
                runtimes,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(5),
                Duration.ofMinutes(10),
                Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                new MediaProperties.Upload(10_000_000, List.of("jpg"), List.of("jpg"), Duration.ofMinutes(15)),
                new MediaProperties.S3(
                        null,
                        null,
                        "us-east-1",
                        true,
                        "uploads",
                        "media",
                        null,
                        null,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(10)));

        Declarables topology = new MediaTopologyConfig().mediaTopology(properties);

        assertThat(topology.getDeclarablesByType(DirectExchange.class))
                .extracting(DirectExchange::getName)
                .contains("media", "media.dlx", "provider", "provider.dlx");
        assertThat(topology.getDeclarablesByType(Queue.class))
                .extracting(Queue::getName)
                .contains(
                        "provider.unrouted",
                        "provider.jobs",
                        "provider.video.jobs",
                        "provider.results",
                        "provider.progress",
                        "provider.jobs.dlq",
                        "provider.video.jobs.dlq",
                        "provider.results.dlq",
                        "provider.progress.dlq");

        for (String name : List.of("provider.jobs", "provider.video.jobs")) {
            Queue jobs = topology.getDeclarablesByType(Queue.class).stream()
                    .filter(queue -> queue.getName().equals(name))
                    .findFirst()
                    .orElseThrow();
            assertThat(jobs.getArguments()).as(name)
                    .containsOnly(
                            Map.entry("x-queue-type", "quorum"),
                            Map.entry("x-delivery-limit", 3),
                            Map.entry("x-dead-letter-exchange", "provider.dlx"),
                            Map.entry("x-consumer-timeout", Duration.ofMinutes(20).toMillis()));
        }
        assertThat(topology.getDeclarablesByType(Binding.class))
                .extracting(Binding::getDestination, Binding::getExchange, Binding::getRoutingKey)
                .contains(
                        tuple("provider.jobs", "provider", "job"),
                        tuple("provider.video.jobs", "provider", "video.job"),
                        tuple("provider.jobs.dlq", "provider.dlx", "job"),
                        tuple("provider.video.jobs.dlq", "provider.dlx", "video.job"));
    }

    @Test
    void onlyVideoClipsAreRoutedToTheVideoQueue() {
        assertThat(MediaTopologyConfig.providerJobKey("generate_video")).isEqualTo("video.job");
        for (String capability : List.of("generate_script", "generate_storyboard", "generate_speech")) {
            assertThat(MediaTopologyConfig.providerJobKey(capability)).as(capability).isEqualTo("job");
        }
    }
}
