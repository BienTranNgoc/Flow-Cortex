package com.flowcortex.media.api.provider;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Contract returned by {@code generate_video/vertical_ad_clip_v1}. */
public record ProviderVideoData(
        @NotBlank @Pattern(regexp = "vertical_ad_clip_v1") String profile,
        @NotNull Integer durationSeconds,
        @NotBlank @Pattern(regexp = "seedance|veo|wan|fixture") String provider,
        @NotBlank @Size(max = 200) String model,
        @NotBlank @Pattern(regexp = "9:16") String aspectRatio,
        @NotBlank @Pattern(regexp = "480p|720p") String resolution,
        @NotNull UUID sourceRevisionId,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String idempotencyKey,
        @NotBlank @Size(max = 200) String providerTaskId,
        @NotBlank @Pattern(regexp = "mp4") String format) {

    @AssertTrue(message = "durationSeconds must be 5")
    @JsonIgnore
    public boolean isFiveSeconds() {
        return durationSeconds != null && durationSeconds == 5;
    }
}
