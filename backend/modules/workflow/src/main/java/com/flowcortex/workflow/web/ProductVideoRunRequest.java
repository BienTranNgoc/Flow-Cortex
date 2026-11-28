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
        @Size(max = 500) String cta,
        @Size(max = 200) String price,
        @Size(max = 500) String promotion,
        @NotNull @Size(min = 1, max = 3) List<@NotNull UUID> uploadIds,
        @NotNull @Valid VideoConfig video,
        Boolean acknowledgeCost,
        @Pattern(regexp = "showroom-v1|mc-v1") String templateId,
        @Pattern(regexp = "male|female") String voiceGender,
        @Size(max = 64) String mcPresetId,
        UUID mcUploadId,
        @Size(max = 64) String backgroundPresetId,
        UUID backgroundUploadId,
        @Size(max = 500) String sceneNote,
        @Pattern(regexp = "voice_bgm|music_only") String audioMode) {

    ProductVideoRunRequest(String productName, String description, String cta, String price, String promotion,
            List<UUID> uploadIds, VideoConfig video, Boolean acknowledgeCost) {
        this(productName, description, cta, price, promotion, uploadIds, video, acknowledgeCost,
                null, null, null, null, null, null, null, null);
    }

    record VideoConfig(@NotNull @Pattern(regexp = "9:16") String aspectRatio) {
    }

    @AssertTrue(message = "uploadIds must not contain duplicates")
    @JsonIgnore
    boolean hasDistinctUploads() {
        return uploadIds == null || uploadIds.stream().distinct().count() == uploadIds.size();
    }

    @AssertTrue(message = "price, promotion, and cta must be omitted or contain text")
    @JsonIgnore
    boolean hasNoBlankOptionalClaims() {
        return notBlankWhenPresent(price) && notBlankWhenPresent(promotion) && notBlankWhenPresent(cta);
    }

    @AssertTrue(message = "mcPresetId and mcUploadId cannot both be set")
    @JsonIgnore
    boolean hasSingleMcSource() {
        return mcPresetId == null || mcPresetId.isBlank() || mcUploadId == null;
    }

    @AssertTrue(message = "backgroundPresetId and backgroundUploadId cannot both be set")
    @JsonIgnore
    boolean hasSingleBackgroundSource() {
        return backgroundPresetId == null || backgroundPresetId.isBlank() || backgroundUploadId == null;
    }

    @AssertTrue(message = "mcUploadId and backgroundUploadId must be distinct from product uploadIds")
    @JsonIgnore
    boolean hasDistinctExtraUploads() {
        if (mcUploadId != null && mcUploadId.equals(backgroundUploadId)) {
            return false;
        }
        if (uploadIds == null) {
            return true;
        }
        return (mcUploadId == null || !uploadIds.contains(mcUploadId))
                && (backgroundUploadId == null || !uploadIds.contains(backgroundUploadId));
    }

    @AssertTrue(message = "audioMode music_only is only supported for showroom-v1")
    @JsonIgnore
    boolean hasSupportedAudioModeForTemplate() {
        return !"music_only".equals(audioMode) || !"mc-v1".equals(templateId);
    }

    ProductVideoDraft draft() {
        return ProductVideoDraft.of(productName, description, cta, price, promotion, video.aspectRatio(),
                templateId, voiceGender, mcPresetId, backgroundPresetId, sceneNote, audioMode);
    }

    private static boolean notBlankWhenPresent(String value) {
        return value == null || !value.isBlank();
    }
}
