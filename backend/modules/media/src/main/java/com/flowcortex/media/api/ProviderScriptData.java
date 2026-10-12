package com.flowcortex.media.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Typed {@code data} payload returned by {@code generate_script/product_ad_v1}; see
 * {@code contracts/provider-script-result.schema.json}. Runtime-validated at the trust boundary so a
 * broker-redelivered, unsanitized, or non-conforming response from the provider worker cannot land
 * in {@code run_step.output}. */
public record ProviderScriptData(
        @NotBlank @Pattern(regexp = "product_ad_v1") String profile,
        @NotBlank @Pattern(regexp = "vi") String language,
        @NotBlank @Size(max = 8000) String script) {

    @AssertTrue(message = "language must be 'vi'")
    @JsonIgnore
    public boolean isVietnamese() {
        return language != null && language.equals("vi");
    }

    @AssertTrue(message = "profile must be 'product_ad_v1'")
    @JsonIgnore
    public boolean isProductAdV1() {
        return profile != null && profile.equals("product_ad_v1");
    }
}
