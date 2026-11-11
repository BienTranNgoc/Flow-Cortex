package com.flowcortex.chat.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code flowcortex.chat.*}; every value has its default in {@code application.yml}.
 * Read once at startup, so a change needs a restart.
 */
@Validated
@ConfigurationProperties("flowcortex.chat")
public record ChatProperties(
        boolean enabled,
        @NotBlank String model,
        @NotNull @Valid Quota quota,
        @NotNull Duration turnTimeout,
        @NotNull Duration sweepGrace,
        @Min(1) int maxToolRounds,
        @Min(1) int turnThreads,
        @Min(1) int turnQueue,
        @Min(256) @Max(MAX_OUTPUT_TOKENS) int maxOutputTokens,
        @Min(0) int thinkingBudget) {

    /** The most the provider takes in one answer; thinking tokens count inside it. */
    static final int MAX_OUTPUT_TOKENS = 32768;

    public ChatProperties {
        if (thinkingBudget >= maxOutputTokens) {
            throw new IllegalArgumentException("flowcortex.chat.thinking-budget must be below max-output-tokens");
        }
    }

    /** Messages per day for one member and over everyone, and messages per minute for one member. */
    public record Quota(@Min(1) int memberPerDay, @Min(1) int globalPerDay, @Min(1) int perMinute) {
    }
}
