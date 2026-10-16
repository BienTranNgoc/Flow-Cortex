package com.flowcortex.workflow.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.flowcortex.workflow.api.ProductVideoDraft;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

record ProductVideoRunRequest(
        @NotBlank @Size(max = 200) String productName,
        @NotBlank @Size(max = 4000) String description,
        @NotBlank @Size(max = 500) String cta,
        @Size(max = 200) String price,
        @Size(max = 500) String promotion,
        @NotNull @Size(min = 1, max = 10) List<@NotNull UUID> uploadIds,
        @NotNull @Valid VideoConfig video) {

    record VideoConfig(@NotNull @Pattern(regexp = "9:16") String aspectRatio) {
    }

    @AssertTrue(message = "uploadIds must not contain duplicates")
    @JsonIgnore
    boolean hasDistinctUploads() {
        return uploadIds == null || uploadIds.stream().distinct().count() == uploadIds.size();
    }

    @AssertTrue(message = "price and promotion must be omitted or contain text")
    @JsonIgnore
    boolean hasNoBlankOptionalClaims() {
        return notBlankWhenPresent(price) && notBlankWhenPresent(promotion);
    }

    ProductVideoDraft draft() {
        return new ProductVideoDraft(productName.trim(), description.trim(), cta.trim(), trimToNull(price),
                trimToNull(promotion), video.aspectRatio());
    }

    private static boolean notBlankWhenPresent(String value) {
        return value == null || !value.isBlank();
    }

    private static String trimToNull(String value) {
        return value == null ? null : value.trim();
    }
}
