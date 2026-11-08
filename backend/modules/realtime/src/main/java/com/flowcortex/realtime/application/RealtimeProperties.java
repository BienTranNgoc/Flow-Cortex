package com.flowcortex.realtime.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code flowcortex.realtime.*}; {@code application.yml} carries the values. The stream
 * caps are per backend instance.
 */
@Validated
@ConfigurationProperties("flowcortex.realtime")
public record RealtimeProperties(
        @NotNull Duration heartbeat,
        @NotNull @Valid Replay replay,
        @Positive int maxStreamsPerUser,
        @Positive int maxTopicsPerStream,
        @Positive int queueMaxLength,
        @Positive int hubThreads,
        @NotNull @Valid Publish publish) {

    /** The in-memory ring a reconnecting stream resumes from. */
    public record Replay(@Positive int maxEvents, @NotNull Duration maxAge) {
    }

    /** The bounded hand-off between a publisher's thread and the broker. */
    public record Publish(@Positive int queueCapacity) {
    }
}
