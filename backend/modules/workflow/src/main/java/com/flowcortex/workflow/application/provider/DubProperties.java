package com.flowcortex.workflow.application.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The dub steps' settings, bound from {@code flowcortex.dub.*} ({@code application.yml} carries the
 * defaults and env names). {@code charsPerSecond} is how fast a voice reads Vietnamese; a line is
 * reworded before voicing when it would take more than {@code shortenAbove} times its slot.
 */
@Validated
@ConfigurationProperties("flowcortex.dub")
public record DubProperties(
        @NotNull Boolean enabled,
        @Positive double charsPerSecond,
        @Positive double shortenAbove,
        @NotEmpty Map<String, @NotBlank String> voices,
        @NotNull @Valid Pool pool) {

    /**
     * The threads the provider-backed steps run on, apart from the shared async pool: {@code size}
     * run at once and {@code queueCapacity} wait; a step arriving with the queue full fails at once.
     */
    public record Pool(@Positive int size, @PositiveOrZero int queueCapacity) {

        /**
         * How long a step may wait for a thread: the queue drains in rounds of {@code size}, each
         * lasting at most one step's {@code maxRuntime}.
         */
        public Duration queueWait(Duration maxRuntime) {
            int rounds = (queueCapacity + size - 1) / size;
            return maxRuntime.multipliedBy(rounds);
        }
    }
}
