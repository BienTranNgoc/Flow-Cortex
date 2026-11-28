package com.flowcortex.media.api.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The {@code params.payload} of a {@link ProviderJob}, as {@code contracts/provider-job.schema.json}
 * defines it. {@code ProviderJob.params} is a {@code Map<String, Object>}, whose contents no
 * annotation can bound, so the payload is converted to one of these before the job is published.
 */
public final class ProviderJobPayload {

    private ProviderJobPayload() {
    }

    /** {@code productAdParams.payload} of a {@code generate_script/product_ad_v1} step. */
    public record ProductAd(
            @NotBlank @Size(max = 200) String productName,
            @NotBlank @Size(max = 4000) String description,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max = 500) String cta,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max = 200) String price,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max = 500) String promotion,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Pattern(regexp = "showroom-v1|mc-v1") String templateId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 64) String scenePreset,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 500) String sceneNote) {

        public ProductAd(String productName, String description, String cta, String price, String promotion) {
            this(productName, description, cta, price, promotion, null, null, null);
        }
    }

    /** {@code speechParams.payload} of a {@code generate_speech/vi_voice_v1} step. */
    public record Speech(
            @NotBlank @Size(max = 8000) String script,
            @NotNull UUID sourceRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Pattern(regexp = "male|female") String voiceGender,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 100) String voice,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 2000) String mcHookScript,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 2000) String productCloseScript) {

        public Speech(String script, UUID sourceRevisionId) {
            this(script, sourceRevisionId, null, null, null, null);
        }
    }

    /** {@code storyboardParams.payload} of a Product Video visual-storyboard step. */
    public record Storyboard(
            @NotBlank @Size(max = 8000) String script,
            @NotNull UUID sourceRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Pattern(regexp = "showroom-v1|mc-v1") String templateId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 64) String scenePreset,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 500) String sceneNote,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 2000) String mcHookScript,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 2000) String productCloseScript) {

        public Storyboard(String script, UUID sourceRevisionId) {
            this(script, sourceRevisionId, null, null, null, null, null);
        }
    }

    /** {@code keyframeParams.payload} of a {@code generate_keyframe/vertical_ad_keyframe_v1} step. */
    public record Keyframe(
            @NotBlank @Size(max = 2500) String prompt,
            @NotNull UUID sourceRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 64) String scenePreset,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 500) String sceneNote) {

        public Keyframe(String prompt, UUID sourceRevisionId) {
            this(prompt, sourceRevisionId, null, null);
        }
    }

    /** {@code videoParams.payload} of a {@code generate_video} step. */
    public record Video(
            @NotBlank @Size(max = 2500) String prompt,
            @NotNull UUID sourceRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Min(4) @Max(10) Integer durationSeconds,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Pattern(regexp = "480p|720p|768p") String resolution,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 64) String firstFrameKey) {

        public Video(String prompt, UUID sourceRevisionId) {
            this(prompt, sourceRevisionId, null, null, null);
        }
    }

    /** {@code lipsyncParams.payload} of a {@code lipsync_video/vertical_ad_lipsync_v1} step. */
    public record Lipsync(
            @NotNull UUID sourceRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Min(2) @Max(10) Integer durationSeconds) {

        public Lipsync(UUID sourceRevisionId) {
            this(sourceRevisionId, null);
        }
    }
}
