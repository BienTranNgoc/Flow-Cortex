package com.flowcortex.media.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
            @NotBlank @Size(max = 500) String cta,
            @Size(max = 200) String price,
            @Size(max = 500) String promotion) {
    }

    /** {@code speechParams.payload} of a {@code generate_speech/vi_voice_v1} step. */
    public record Speech(@NotBlank @Size(max = 8000) String script, @NotNull UUID sourceRevisionId) {
    }
}
