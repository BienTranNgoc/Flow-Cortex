package com.flowcortex.workflow.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Confirmed product data snapshotted when a Product Video run starts. {@link #of} is the validating entry point:
 * it refuses what the Product Video form refuses and returns the trimmed draft.
 */
public record ProductVideoDraft(
        @NotBlank @Size(max = 200) String productName,
        @NotBlank @Size(max = 4000) String description,
        @Size(max = 500) String cta,
        @Size(max = 200) String price,
        @Size(max = 500) String promotion,
        @NotNull @Pattern(regexp = "9:16") String aspectRatio) {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * @throws ConstraintViolationException (a 400 with field errors) if a value is blank, over its length, or the
     *         aspect ratio is not {@code 9:16}; an optional field is either omitted or holds text
     */
    public static ProductVideoDraft of(String productName, String description, String cta, String price,
            String promotion, String aspectRatio) {
        ProductVideoDraft raw = new ProductVideoDraft(productName, description, cta, price, promotion, aspectRatio);
        Set<ConstraintViolation<ProductVideoDraft>> violations = VALIDATOR.validate(raw);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return new ProductVideoDraft(productName.trim(), description.trim(), trimToNull(cta), trimToNull(price),
                trimToNull(promotion), aspectRatio);
    }

    /** Product Video takes exactly one image, and the same upload is never named twice. */
    public static void requireUploads(List<UUID> uploadIds) {
        Set<ConstraintViolation<UploadSelection>> violations = VALIDATOR.validate(new UploadSelection(uploadIds));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    @AssertTrue(message = "price, promotion, and cta must be omitted or contain text")
    boolean hasNoBlankOptionalClaims() {
        return notBlankWhenPresent(price) && notBlankWhenPresent(promotion) && notBlankWhenPresent(cta);
    }

    private static boolean notBlankWhenPresent(String value) {
        return value == null || !value.isBlank();
    }

    private static String trimToNull(String value) {
        return value == null ? null : value.trim();
    }

    private record UploadSelection(@NotNull @Size(min = 1, max = 1) List<@NotNull UUID> uploadIds) {

        @AssertTrue(message = "uploadIds must not contain duplicates")
        boolean hasDistinctUploads() {
            return uploadIds == null || uploadIds.stream().distinct().count() == uploadIds.size();
        }
    }
}
