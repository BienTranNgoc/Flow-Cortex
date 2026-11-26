package com.flowcortex.asset.web;

import com.flowcortex.asset.application.AssetViews;
import com.flowcortex.asset.application.AssetViews.GroupPage;
import com.flowcortex.asset.application.AssetViews.Page;
import com.flowcortex.asset.application.AssetViews.SpaceUsage;
import com.flowcortex.asset.application.AssetViews.Tag;
import com.flowcortex.asset.application.RunResults;
import com.flowcortex.media.api.AssetFileLink;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The wire shapes of the library routes. */
final class AssetResponses {

    private AssetResponses() {
    }

    /**
     * One asset. {@code workflowId} and {@code workflowName} are null for an upload; {@code thumbnailUrl} is a
     * signed link and is null unless the asset is an image that is not missing.
     */
    record AssetItemResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"video", "image", "audio"}) String kind,
            @Schema(requiredMode = RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"run", "upload"}) String source,
            @Schema(requiredMode = RequiredMode.REQUIRED) String mimeType,
            @Schema(requiredMode = RequiredMode.REQUIRED) long bytes,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID runId,
            UUID workflowId,
            String workflowName,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean favorite,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> tags,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean missing,
            String thumbnailUrl) {

        static AssetItemResponse from(AssetViews.Item item) {
            return new AssetItemResponse(item.id(), item.kind(), item.title(), item.source(), item.mimeType(),
                    item.bytes(), item.createdAt(), item.runId(), item.workflowId(), item.workflowName(),
                    item.favorite(), item.tags(), item.missing(), item.thumbnailUrl());
        }
    }

    /** Matches under every filter except {@code kind}. */
    record AssetCountsResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) long all,
            @Schema(requiredMode = RequiredMode.REQUIRED) long video,
            @Schema(requiredMode = RequiredMode.REQUIRED) long image,
            @Schema(requiredMode = RequiredMode.REQUIRED) long audio,
            @Schema(requiredMode = RequiredMode.REQUIRED) long favorite) {
    }

    /** {@code total} is under every filter; {@code nextCursor} is null on the last page. */
    record AssetPageMetaResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) long total,
            @Schema(requiredMode = RequiredMode.REQUIRED) AssetCountsResponse counts,
            String nextCursor,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant mark) {
    }

    record AssetPageResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<AssetItemResponse> items,
            @Schema(requiredMode = RequiredMode.REQUIRED) AssetPageMetaResponse meta) {

        static AssetPageResponse from(Page page) {
            return new AssetPageResponse(page.items().stream().map(AssetItemResponse::from).toList(),
                    new AssetPageMetaResponse(page.total(),
                            new AssetCountsResponse(page.counts().all(), page.counts().video(),
                                    page.counts().image(), page.counts().audio(), page.counts().favorite()),
                            page.nextCursor(), page.mark()));
        }
    }

    /** {@code key} is {@code workflow:<id>}, {@code upload} or {@code day:<YYYY-MM-DD>}; {@code title} is null for uploads. */
    record AssetGroupResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String key,
            String title,
            @Schema(requiredMode = RequiredMode.REQUIRED) long count,
            @Schema(requiredMode = RequiredMode.REQUIRED) long runCount,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant latestAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<AssetItemResponse> items) {
    }

    record AssetGroupPageMetaResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) long totalGroups,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant mark) {
    }

    record AssetGroupPageResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<AssetGroupResponse> groups,
            @Schema(requiredMode = RequiredMode.REQUIRED) AssetGroupPageMetaResponse meta) {

        static AssetGroupPageResponse from(GroupPage page) {
            return new AssetGroupPageResponse(
                    page.groups().stream()
                            .map(group -> new AssetGroupResponse(group.key(), group.title(), group.count(),
                                    group.runCount(), group.latestAt(),
                                    group.items().stream().map(AssetItemResponse::from).toList()))
                            .toList(),
                    new AssetGroupPageMetaResponse(page.totalGroups(), page.mark()));
        }
    }

    record AssetUsageEntryResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) long count,
            @Schema(requiredMode = RequiredMode.REQUIRED) long bytes) {

        static AssetUsageEntryResponse from(AssetViews.Usage usage) {
            return new AssetUsageEntryResponse(usage.count(), usage.bytes());
        }
    }

    record AssetUsageByKindResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) AssetUsageEntryResponse video,
            @Schema(requiredMode = RequiredMode.REQUIRED) AssetUsageEntryResponse image,
            @Schema(requiredMode = RequiredMode.REQUIRED) AssetUsageEntryResponse audio) {
    }

    /** Every row of the space counts, missing assets included. */
    record AssetUsageResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) AssetUsageEntryResponse total,
            @Schema(requiredMode = RequiredMode.REQUIRED) AssetUsageByKindResponse byKind) {

        static AssetUsageResponse from(SpaceUsage usage) {
            return new AssetUsageResponse(AssetUsageEntryResponse.from(usage.total()),
                    new AssetUsageByKindResponse(AssetUsageEntryResponse.from(usage.video()),
                            AssetUsageEntryResponse.from(usage.image()), AssetUsageEntryResponse.from(usage.audio())));
        }
    }

    /** A signed link to the stored file, valid until {@code expiresAt}. */
    record AssetLinkResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String url,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt) {

        static AssetLinkResponse from(AssetFileLink link) {
            return new AssetLinkResponse(link.url(), link.expiresAt());
        }
    }

    /** One signed thumbnail of a run's output image. */
    record RunResultThumbnailResponse(@Schema(requiredMode = RequiredMode.REQUIRED) String url) {
    }

    /**
     * What a run produced: its file count, kinds newest first, at most three thumbnails, and whether its
     * outputs are still being saved ({@code pending}), saved ({@code done}) or neither ({@code none}).
     */
    record RunResultResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID runId,
            @Schema(requiredMode = RequiredMode.REQUIRED) long count,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> kinds,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<RunResultThumbnailResponse> thumbnails,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"pending", "done", "none"})
            String registration) {

        static RunResultResponse from(RunResults.RunResult result) {
            return new RunResultResponse(result.runId(), result.count(), result.kinds(),
                    result.thumbnails().stream().map(RunResultThumbnailResponse::new).toList(),
                    result.registration().value());
        }
    }

    record RunResultListResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<RunResultResponse> results) {

        static RunResultListResponse from(List<RunResults.RunResult> results) {
            return new RunResultListResponse(results.stream().map(RunResultResponse::from).toList());
        }
    }

    record AssetTagResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String label,
            @Schema(requiredMode = RequiredMode.REQUIRED) long count) {
    }

    record AssetTagListResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<AssetTagResponse> tags) {

        static AssetTagListResponse from(List<Tag> tags) {
            return new AssetTagListResponse(
                    tags.stream().map(tag -> new AssetTagResponse(tag.label(), tag.count())).toList());
        }
    }
}
