package com.flowcortex.asset.application;

import com.flowcortex.asset.domain.AssetFileNames;
import com.flowcortex.asset.infrastructure.AssetLibraryQueries;
import com.flowcortex.asset.infrastructure.AssetLibraryQueries.AssetRow;
import com.flowcortex.asset.infrastructure.AssetMissingMarks;
import com.flowcortex.media.api.AssetFileLink;
import com.flowcortex.media.api.AssetFiles;
import com.flowcortex.media.api.AssetFiles.Disposition;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The download and view links of one asset. Each request asks the object store whether the file is still
 * there: a file found gone is marked missing, a marked file found again is cleared, and a store that does
 * not answer changes nothing. Logs carry the asset id and an outcome code, never a title or a URL.
 */
@Service
public class AssetLinks {

    private static final Logger log = LoggerFactory.getLogger(AssetLinks.class);

    private final AssetLibraryQueries queries;
    private final AssetMissingMarks marks;
    private final AssetFiles files;

    public AssetLinks(AssetLibraryQueries queries, AssetMissingMarks marks, AssetFiles files) {
        this.queries = queries;
        this.marks = marks;
        this.files = files;
    }

    /**
     * A link whose response saves the file under the asset's title and the extension of its type.
     *
     * @throws com.flowcortex.shared.web.NotFoundException 404 {@code asset.notFound} for a malformed id, an unknown
     *         one and another space's
     * @throws LocalizedStatusException 410 {@code asset.fileGone} when the object is gone, 503
     *         {@code asset.storeUnavailable} when the store does not answer or the asset's bucket is not the one
     *         it serves (nothing is marked missing)
     */
    public AssetFileLink downloadUrl(UUID spaceId, String idText) {
        return link(spaceId, idText, Disposition.ATTACHMENT, "download");
    }

    /**
     * A link whose response is shown inline, so the browser streams the file from the store; the same checks
     * and refusals as {@link #downloadUrl}.
     */
    public AssetFileLink viewUrl(UUID spaceId, String idText) {
        return link(spaceId, idText, Disposition.INLINE, "view");
    }

    private AssetFileLink link(UUID spaceId, String idText, Disposition disposition, String operation) {
        UUID id = SpaceAssetLibrary.parseId(idText);
        if (id == null) {
            log.info("asset.link.{} space={} outcome=not_found", operation, spaceId);
            throw SpaceAssetLibrary.notFound();
        }
        AssetRow row = queries.one(spaceId, id).orElseThrow(() -> {
            log.info("asset.link.{} space={} asset={} outcome=not_found", operation, spaceId, id);
            return SpaceAssetLibrary.notFound();
        });

        if (!files.serves(row.bucket())) {
            // An operator or configuration fault, not proof the member's file is gone: nothing is marked missing.
            log.warn("asset.link.{} space={} asset={} outcome=outside_media_bucket", operation, spaceId, id);
            throw storeUnavailable();
        }

        OptionalLong size;
        try {
            size = files.probe(row.bucket(), row.objectKey());
        } catch (MediaStoreUnavailableException e) {
            log.warn("asset.link.{} space={} asset={} outcome=store_unavailable", operation, spaceId, id);
            throw storeUnavailable();
        }
        if (size.isEmpty()) {
            marks.markGone(id, spaceId);
            log.info("asset.link.{} space={} asset={} outcome=file_gone", operation, spaceId, id);
            throw new LocalizedStatusException(HttpStatus.GONE, new LocalizedMessage("asset.fileGone"));
        }
        if (row.missing()) {
            marks.clear(id, spaceId);
        }

        AssetFileLink link = files.sign(row.bucket(), row.objectKey(), disposition,
                AssetFileNames.fileName(id, row.title(), row.mimeType()), row.mimeType());
        log.info("asset.link.{} space={} asset={} outcome=issued", operation, spaceId, id);
        return link;
    }

    private static LocalizedStatusException storeUnavailable() {
        return new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                new LocalizedMessage("asset.storeUnavailable"));
    }
}
