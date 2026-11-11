package com.flowcortex.drive.memory;

import com.flowcortex.drive.domain.DriveRoles.ContentType;
import com.flowcortex.drive.domain.MemoryFilter;
import com.flowcortex.drive.domain.MemoryFilter.SizeBucket;
import com.flowcortex.drive.memory.MemoryResultRepository.Flag;
import com.flowcortex.drive.memory.MemoryResultRepository.Stored;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.RunResults;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and flags the Memory of one space; an entry of another space is indistinguishable from a missing one. */
@Service
class MemoryService {

    static final int PINNED_STRIP = 20;

    private final MemoryResultRepository memory;
    private final MediaJobs mediaJobs;
    private final RunResults runResults;

    MemoryService(MemoryResultRepository memory, MediaJobs mediaJobs, RunResults runResults) {
        this.memory = memory;
        this.mediaJobs = mediaJobs;
        this.runResults = runResults;
    }

    @Transactional(readOnly = true)
    MemoryListResponse list(UUID spaceId, MemoryFilter filter) {
        MemoryResultRepository.Page page = memory.list(spaceId, filter);
        Map<SizeBucket, Long> counts = memory.bucketCounts(spaceId, filter);
        List<MemoryEntryResponse> pinned = memory.pinned(spaceId, PINNED_STRIP).stream().map(this::response).toList();
        return new MemoryListResponse(page.items().stream().map(this::response).toList(), page.total(),
                filter.page(), filter.pageSize(), pinned,
                new MemoryListResponse.SizeBuckets(counts.get(SizeBucket.LT_1MB),
                        counts.get(SizeBucket.BETWEEN_1_AND_50MB), counts.get(SizeBucket.GT_50MB)));
    }

    void setFlag(UUID spaceId, UUID id, Flag flag, boolean value) {
        if (memory.setFlag(spaceId, id, flag, value) == 0) {
            throw notFound();
        }
    }

    /** The full step output behind a text entry; a media entry, or a run whose output is gone, is not found. */
    @Transactional(readOnly = true)
    MemoryTextResponse text(UUID spaceId, UUID ownerId, UUID id) {
        Stored row = memory.find(spaceId, id).filter(entry -> entry.kind() == ContentType.TEXT)
                .orElseThrow(MemoryService::notFound);
        return new MemoryTextResponse(runResults.stepOutput(ownerId, row.runId(), row.stepOrder())
                .orElseThrow(MemoryService::notFound));
    }

    private MemoryEntryResponse response(Stored row) {
        if (row.kind() == ContentType.TEXT) {
            return MemoryEntryResponse.of(row, null, null);
        }
        MediaDownload download = mediaJobs.presignedGet(row.bucket(), row.objectKey(), row.fileName());
        return MemoryEntryResponse.of(row, download.url(), download.expiresAt());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(new LocalizedMessage("drive.memory.notFound"));
    }
}
