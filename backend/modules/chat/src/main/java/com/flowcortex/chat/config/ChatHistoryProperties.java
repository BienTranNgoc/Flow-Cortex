package com.flowcortex.chat.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code flowcortex.chat.history.*}; the default is in {@code application.yml}. Kept apart from
 * {@link ChatProperties} so that record's constructor does not change. Read once at startup.
 */
@Validated
@ConfigurationProperties("flowcortex.chat.history")
public record ChatHistoryProperties(@Min(1) int maxFolders) {
}
