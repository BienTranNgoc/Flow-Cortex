package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Typed {@code data} payload returned by {@code generate_speech/vi_voice_v1}; see
 * {@code contracts/provider-speech-result.schema.json}. Bounds {@code durationMs} so a worker that
 * under-reports duration fails the step at the trust boundary, not later in the renderer. */
public record ProviderSpeechData(
        @NotBlank @Pattern(regexp = "vi_voice_v1") String profile,
        @NotNull @Min(1) Integer durationMs,
        @NotBlank @Size(max = 50) String provider,
        @NotBlank @Size(max = 100) String voice,
        @NotBlank @Pattern(regexp = "[+-][0-9]{1,3}%") String speed,
        @NotNull UUID sourceRevisionId,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String idempotencyKey,
        @NotBlank @Pattern(regexp = "wav") String format) {

    @AssertTrue(message = "profile must be 'vi_voice_v1'")
    @JsonIgnore
    public boolean isVietnameseVoiceV1() {
        return profile != null && profile.equals("vi_voice_v1");
    }
}
