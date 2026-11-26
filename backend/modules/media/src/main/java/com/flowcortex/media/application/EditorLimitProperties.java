package com.flowcortex.media.application;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The configured layer of the video editor's runtime limits, bound from
 * {@code flowcortex.media.editor.*} ({@code application.yml} carries the starting values and env
 * names). The bounds live only in {@link EditorLimitSettings}, where the settings registry checks
 * each value against them at startup.
 */
@Validated
@ConfigurationProperties("flowcortex.media.editor")
public record EditorLimitProperties(
        @NotNull Integer projectMaxVersions,
        @NotNull Integer projectMaxBytes,
        @NotNull Integer batchMaxItems,
        @NotNull Integer takeCheckDailyMax,
        @NotNull Integer stemsMaxDurationSec,
        @NotNull Integer speakersMaxDurationSec,
        @NotNull Integer deriveMaxDurationSec,
        @NotNull Integer beatsMaxDurationSec,
        @NotNull Integer audioToolsMaxDurationSec) {
}
