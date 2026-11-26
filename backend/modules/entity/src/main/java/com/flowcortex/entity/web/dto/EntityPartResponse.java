package com.flowcortex.entity.web.dto;

import com.flowcortex.asset.api.SpaceAssetSummary;
import com.flowcortex.entity.application.EntityView.PartView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.UUID;

/** A part and the library asset it points at; the asset carries no URL. */
public record EntityPartResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String role,
        String label,
        @Schema(requiredMode = RequiredMode.REQUIRED) int sort,
        @Schema(requiredMode = RequiredMode.REQUIRED) EntityPartAsset asset) {

    /** What a part shows of its asset. */
    public record EntityPartAsset(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"video", "image", "audio"}) String kind,
            @Schema(requiredMode = RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = RequiredMode.REQUIRED) String mimeType) {

        static EntityPartAsset from(SpaceAssetSummary asset) {
            return new EntityPartAsset(asset.id(), asset.kind(), asset.title(), asset.mimeType());
        }
    }

    public static EntityPartResponse from(PartView part) {
        return new EntityPartResponse(part.id(), part.role(), part.label(), part.sort(),
                EntityPartAsset.from(part.asset()));
    }
}
