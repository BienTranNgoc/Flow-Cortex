package com.flowcortex.onboarding.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Locale;

/** Answer vocabularies for the published contract only, so the client gets literal unions for the list answers. */
final class OnboardingChoices {

    private OnboardingChoices() {
    }

    /** Maps stored vocabulary values (the lower-cased constant names) to constants; null stays null. */
    static <E extends Enum<E>> List<E> of(List<String> values, Class<E> type) {
        if (values == null) {
            return null;
        }
        return values.stream().map(value -> Enum.valueOf(type, value.toUpperCase(Locale.ROOT))).toList();
    }

    @Schema(name = "OnboardingPurpose")
    enum Purpose {
        @JsonProperty("marketing_content") MARKETING_CONTENT,
        @JsonProperty("automation") AUTOMATION,
        @JsonProperty("documents_reports") DOCUMENTS_REPORTS,
        @JsonProperty("customer_care") CUSTOMER_CARE,
        @JsonProperty("research") RESEARCH,
        @JsonProperty("other") OTHER
    }

    @Schema(name = "OnboardingIndustry")
    enum Industry {
        @JsonProperty("marketing") MARKETING,
        @JsonProperty("sales") SALES,
        @JsonProperty("finance") FINANCE,
        @JsonProperty("hr") HR,
        @JsonProperty("tech") TECH,
        @JsonProperty("other") OTHER
    }

    @Schema(name = "OnboardingContentType")
    enum ContentType {
        @JsonProperty("text") TEXT,
        @JsonProperty("image") IMAGE,
        @JsonProperty("audio") AUDIO,
        @JsonProperty("video") VIDEO,
        @JsonProperty("spreadsheet") SPREADSHEET,
        @JsonProperty("document") DOCUMENT,
        @JsonProperty("slide") SLIDE
    }
}
