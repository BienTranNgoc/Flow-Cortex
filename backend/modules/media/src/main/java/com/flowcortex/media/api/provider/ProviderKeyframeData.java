package com.flowcortex.media.api.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Contract returned by {@code generate_keyframe/vertical_ad_keyframe_v1}. */
public record ProviderKeyframeData(
        @NotBlank @Pattern(regexp = "vertical_ad_keyframe_v1") String profile,
        @NotBlank @Pattern(regexp = "seedream|fixture") String provider,
        @NotBlank @Size(max = 200) String model,
        @NotBlank @Pattern(regexp = "9:16") String aspectRatio,
        @NotNull @Min(240) @Max(4096) Integer width,
        @NotNull @Min(240) @Max(4096) Integer height,
        @NotNull UUID sourceRevisionId,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String idempotencyKey,
        @NotBlank @Size(max = 200) String providerTaskId,
        @NotBlank @Pattern(regexp = "jpg|png") String format,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Pattern(regexp = "[0-9a-f]{64}") String fingerprint,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Pattern(regexp = "[0-9a-f]{64}") String requestKey) {

    public ProviderKeyframeData(String profile, String provider, String model, String aspectRatio,
            Integer width, Integer height, UUID sourceRevisionId, String idempotencyKey,
            String providerTaskId, String format) {
        this(profile, provider, model, aspectRatio, width, height, sourceRevisionId, idempotencyKey,
                providerTaskId, format, null, null);
    }
}
