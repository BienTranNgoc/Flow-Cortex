package com.flowcortex.billing.catalog;

import com.flowcortex.billing.catalog.CatalogueReader.TopupRow;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.UUID;

/** The top-up packages on sale, in order. An English field with no stored copy is null, never the Vietnamese one. */
record TopupPackageListResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<TopupPackageItem> data) {

    /** One package: its price in whole đồng and the credits it adds. */
    record TopupPackageItem(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String nameEn,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String description,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String descriptionEn,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String eyebrow,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String eyebrowEn,
            @Schema(requiredMode = RequiredMode.REQUIRED) long priceVnd,
            @Schema(requiredMode = RequiredMode.REQUIRED) long credits,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String groupKey,
            @Schema(requiredMode = RequiredMode.REQUIRED) int sortOrder) {
    }

    static TopupPackageListResponse from(List<TopupRow> rows) {
        return new TopupPackageListResponse(rows.stream().map(TopupPackageListResponse::item).toList());
    }

    private static TopupPackageItem item(TopupRow row) {
        return new TopupPackageItem(row.id(), row.name(), row.nameEn(), row.description(), row.descriptionEn(),
                row.eyebrow(), row.eyebrowEn(), row.priceVnd(), row.credits(), row.groupKey(), row.sortOrder());
    }
}
