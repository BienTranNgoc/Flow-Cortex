package com.flowcortex.media.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Typed result returned by {@code generate_storyboard/product_ad_storyboard_v1}. */
public record ProviderStoryboardData(
        @NotBlank @Pattern(regexp = "product_ad_storyboard_v1") String profile,
        @NotNull UUID sourceRevisionId,
        @NotBlank @Size(max = 2500) String scene1Prompt,
        @NotBlank @Size(max = 2500) String scene2Prompt) {
}
