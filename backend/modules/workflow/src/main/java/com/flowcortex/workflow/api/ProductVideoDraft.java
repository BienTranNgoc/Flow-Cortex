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
        @NotNull @Pattern(regexp = "9:16") String aspectRatio,
        @Pattern(regexp = "showroom-v1|mc-v1") String templateId,
        @Pattern(regexp = "male|female") String voiceGender,
        @Size(max = 64) String mcPresetId,
        @Size(max = 64) String backgroundPresetId,
        @Size(max = 500) String sceneNote,
        @Pattern(regexp = "voice_bgm|music_only") String audioMode) {

    public ProductVideoDraft(String productName, String description, String cta, String price,
            String promotion, String aspectRatio) {
        this(productName, description, cta, price, promotion, aspectRatio, "showroom-v1", null, null, null, null, null);
    }

    public ProductVideoDraft(String productName, String description, String cta, String price,
            String promotion, String aspectRatio, String templateId, String voiceGender, String mcPresetId,
            String backgroundPresetId, String sceneNote) {
        this(productName, description, cta, price, promotion, aspectRatio, templateId, voiceGender, mcPresetId,
                backgroundPresetId, sceneNote, null);
    }

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * @throws ConstraintViolationException (a 400 with field errors) if a value is blank, over its length, or the
     *         aspect ratio is not {@code 9:16}; an optional field is either omitted or holds text
     */
    public static ProductVideoDraft of(String productName, String description, String cta, String price,
            String promotion, String aspectRatio) {
        return of(productName, description, cta, price, promotion, aspectRatio, "showroom-v1", null,
                null, null, null, null);
    }

    public static ProductVideoDraft of(String productName, String description, String cta, String price,
            String promotion, String aspectRatio, String templateId, String voiceGender, String mcPresetId,
            String backgroundPresetId, String sceneNote) {
        return of(productName, description, cta, price, promotion, aspectRatio, templateId, voiceGender,
                mcPresetId, backgroundPresetId, sceneNote, null);
    }

    public static ProductVideoDraft of(String productName, String description, String cta, String price,
            String promotion, String aspectRatio, String templateId, String voiceGender, String mcPresetId,
            String backgroundPresetId, String sceneNote, String audioMode) {
        String resolvedTemplate = templateId == null || templateId.isBlank() ? "showroom-v1" : templateId.trim();
        String resolvedVoice = voiceGender == null || voiceGender.isBlank() ? null : voiceGender.trim();
        String resolvedAudioMode = audioMode == null || audioMode.isBlank() ? null : audioMode.trim();
        ProductVideoDraft raw = new ProductVideoDraft(productName, description, cta, price, promotion, aspectRatio,
                resolvedTemplate, resolvedVoice, trimToNull(mcPresetId), trimToNull(backgroundPresetId),
                trimToNull(sceneNote), resolvedAudioMode);
        Set<ConstraintViolation<ProductVideoDraft>> violations = VALIDATOR.validate(raw);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return new ProductVideoDraft(productName.trim(), description.trim(), trimToNull(cta), trimToNull(price),
                trimToNull(promotion), aspectRatio, resolvedTemplate, resolvedVoice, trimToNull(mcPresetId),
                trimToNull(backgroundPresetId), trimToNull(sceneNote), resolvedAudioMode);
    }

    /** Product Video takes 1 to 3 product images, and the same upload is never named twice. */
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

    @AssertTrue(message = "audioMode music_only is only supported for showroom-v1")
    boolean hasSupportedAudioModeForTemplate() {
        return !"music_only".equals(audioMode) || !"mc-v1".equals(templateId);
    }

    private static boolean notBlankWhenPresent(String value) {
        return value == null || !value.isBlank();
    }

    private static String trimToNull(String value) {
        return value == null ? null : value.trim();
    }

    private record UploadSelection(@NotNull @Size(min = 1, max = 3) List<@NotNull UUID> uploadIds) {

        @AssertTrue(message = "uploadIds must not contain duplicates")
        boolean hasDistinctUploads() {
            return uploadIds == null || uploadIds.stream().distinct().count() == uploadIds.size();
        }
    }
}
