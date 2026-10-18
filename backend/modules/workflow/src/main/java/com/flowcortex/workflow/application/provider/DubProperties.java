package com.flowcortex.workflow.application.provider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The dub steps' settings, bound from {@code flowcortex.dub.*} ({@code application.yml} carries the
 * defaults and env names). {@code charsPerSecond} is how fast a voice reads Vietnamese; a line is
 * reworded before voicing when it would take more than {@code shortenAbove} times its slot.
 */
@Validated
@ConfigurationProperties("flowcortex.dub")
public record DubProperties(
        @NotNull Boolean enabled,
        @Positive double charsPerSecond,
        @Positive double shortenAbove,
        @NotEmpty Map<String, @NotBlank String> voices,
        @NotNull @Valid Gemini gemini) {

    /** The dub steps may run only with the switch on and a key to call the provider with. */
    public boolean available() {
        return enabled && gemini.hasKey();
    }

    /**
     * Provider settings. Each model gets {@code attempts} tries, separated by {@code retryWaits} in
     * order, each bounded by {@code callTimeout}.
     */
    public record Gemini(
            String apiKey,
            @NotBlank String baseUrl,
            @NotEmpty List<@NotBlank String> translateModels,
            @NotEmpty List<@NotBlank String> ttsModels,
            @Positive int attempts,
            @NotNull List<@NotNull Duration> retryWaits,
            @NotNull Duration callTimeout) {

        public Gemini {
            if (retryWaits != null && attempts > 0 && retryWaits.size() != attempts - 1) {
                throw new IllegalArgumentException("flowcortex.dub.gemini.retry-waits needs one wait between each of "
                        + attempts + " attempts, got " + retryWaits.size());
            }
        }

        boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** The longest one request may take on {@code models} models: every attempt timing out, every wait taken. */
        public Duration worstCase(int models) {
            Duration perModel = callTimeout.multipliedBy(attempts);
            for (Duration wait : retryWaits) {
                perModel = perModel.plus(wait);
            }
            return perModel.multipliedBy(models);
        }

        /** The longest one voice request may take: every attempt on the first voice model, one call on each other. */
        public Duration speechWorstCase() {
            return worstCase(1).plus(callTimeout.multipliedBy(ttsModels.size() - 1L));
        }

        @Override
        public String toString() {
            return "Gemini[apiKey=" + (hasKey() ? "<set>" : "<empty>") + ", baseUrl=" + baseUrl + ", translateModels="
                    + translateModels + ", ttsModels=" + ttsModels + ", attempts=" + attempts + ", retryWaits="
                    + retryWaits + ", callTimeout=" + callTimeout + "]";
        }
    }
}
