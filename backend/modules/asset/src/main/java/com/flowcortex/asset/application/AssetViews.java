package com.flowcortex.asset.application;

import com.flowcortex.asset.domain.AssetFileNames;
import com.flowcortex.asset.infrastructure.AssetLibraryQueries.AssetRow;
import com.flowcortex.media.api.AssetFiles;
import com.flowcortex.media.api.AssetFiles.Disposition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns library rows into what a member sees. An image's thumbnail is signed here and nowhere is the
 * object store called: a file found gone is only known from the mark a link route set.
 */
@Component
public class AssetViews {

    private static final Logger LOG = LoggerFactory.getLogger(AssetViews.class);

    /** One asset as the member sees it; {@code thumbnailUrl} is set only for an image that is not missing. */
    public record Item(UUID id, String kind, String title, String source, String mimeType, long bytes,
            Instant createdAt, UUID runId, UUID workflowId, String workflowName, boolean favorite, List<String> tags,
            boolean missing, String thumbnailUrl) {
    }

    /** Assets under each kind, under every filter but kind; {@code total} also applies the kind filter. */
    public record Counts(long all, long video, long image, long audio, long favorite) {
    }

    /** One page of the list; {@code nextCursor} is null on the last page. */
    public record Page(List<Item> items, long total, Counts counts, String nextCursor, Instant mark) {
    }

    /** One group; {@code title} is null for uploads. */
    public record Group(String key, String title, long count, long runCount, Instant latestAt, List<Item> items) {
    }

    /** One page of groups. */
    public record GroupPage(List<Group> groups, long totalGroups, Instant mark) {
    }

    /** Assets and bytes of one kind, or of all of them. */
    public record Usage(long count, long bytes) {
    }

    /** Storage use of the whole space. */
    public record SpaceUsage(Usage total, Usage video, Usage image, Usage audio) {
    }

    /** A tag in use. */
    public record Tag(String label, long count) {
    }

    private final AssetFiles files;

    public AssetViews(AssetFiles files) {
        this.files = files;
    }

    Item item(AssetRow row, List<String> tags) {
        return new Item(row.id(), row.kind(), row.title(), row.source(), row.mimeType(), row.bytes(),
                row.createdAt(), row.runId(), row.workflowId(), row.workflowName(), row.favorite(), tags,
                row.missing(), thumbnail(row));
    }

    private String thumbnail(AssetRow row) {
        if (!"image".equals(row.kind()) || row.missing()) {
            return null;
        }
        return signedThumbnail(row.id(), row.title(), row.mimeType(), row.bucket(), row.objectKey());
    }

    /** An inline link to an image, or null when its bucket cannot be signed: one such row never fails a page. */
    String signedThumbnail(UUID id, String title, String mimeType, String bucket, String objectKey) {
        try {
            return files.sign(bucket, objectKey, Disposition.INLINE, AssetFileNames.fileName(id, title, mimeType),
                    mimeType).url();
        } catch (IllegalArgumentException e) {
            LOG.warn("asset.thumbnail outcome=unsignable asset={}", id);
            return null;
        }
    }
}
