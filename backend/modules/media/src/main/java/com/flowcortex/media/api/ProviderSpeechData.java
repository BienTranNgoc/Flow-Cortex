package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Typed {@code data} payload returned by {@code generate_speech/vi_voice_v1}; see
 * {@code contracts/provider-speech-result.schema.json}. Bounds {@code durationMs} so a worker that
 * under-reports duration fails the step at the trust boundary, not later in the renderer. */
public record ProviderSpeechData(
        @NotBlank @Pattern(regexp = "vi_voice_v1") String profile,
        @NotNull @Min(1) Integer durationMs) {

    @AssertTrue(message = "profile must be 'vi_voice_v1'")
    @JsonIgnore
    public boolean isVietnameseVoiceV1() {
        return profile != null && profile.equals("vi_voice_v1");
    }
}
