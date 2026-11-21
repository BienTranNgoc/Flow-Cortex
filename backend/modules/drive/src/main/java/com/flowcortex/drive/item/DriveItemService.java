package com.flowcortex.drive.item;

import com.flowcortex.drive.domain.DriveItemRules;
import com.flowcortex.drive.domain.DriveItemRules.CreateInput;
import com.flowcortex.drive.domain.DriveItemRules.UpdateInput;
import com.flowcortex.drive.domain.DriveRoles;
import com.flowcortex.drive.domain.DriveRoles.ContentType;
import com.flowcortex.drive.domain.ResultRules;
import com.flowcortex.drive.item.DriveItemRepository.UploadUsage;
import com.flowcortex.drive.web.DriveCaller.Caller;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.api.StoredDriveFile;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResultView.Output;
import com.flowcortex.workflow.api.RunResults;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Items of a personal space. Nothing here runs in a transaction: an uploaded file is copied before its row is
 * written (a video copy can take minutes), and the copy is confirmed only after the row has committed, so a
 * confirmed copy always has an item and a pending one expires on its own.
 */
@Service
class DriveItemService {

    private static final Logger LOG = LoggerFactory.getLogger(DriveItemService.class);
    private static final int FILE_NAME_MAX = 255;
    private static final String VALUE_INVALID = "drive.value.invalid";
    private static final String RUN_OUTPUT_FIELD = "runOutput";

    /** A create request after parsing; exactly the facts the rules and the sources need. */
    record NewItem(String name, String contentType, String semanticRole, String textValue, UUID uploadId,
            RunOutput runOutput) {
    }

    /** The output of a run an item points at, or a suggestion offers. */
    record RunOutput(UUID runId, Integer stepOrder, String name) {
    }

    /** A patch: every {@code null} field means unchanged. */
    record ItemPatch(String name, String semanticRole, String textValue) {
    }

    /** An item with the time-limited view URL of its file ({@code null} for a text item). */
    record ItemView(DriveItemRow item, MediaDownload download) {
    }

    private final DriveItemRepository repository;
    private final MediaJobs mediaJobs;
    private final RunResults runResults;
    private final Clock clock;
    private final DriveProperties properties;

    DriveItemService(DriveItemRepository repository, MediaJobs mediaJobs, RunResults runResults, Clock clock,
            DriveProperties properties) {
        this.repository = repository;
        this.mediaJobs = mediaJobs;
        this.runResults = runResults;
        this.clock = clock;
        this.properties = properties;
    }

    DriveItemRow create(Caller caller, NewItem item) {
        Map<String, String> errors = DriveItemRules.validateCreate(new CreateInput(item.name(), item.contentType(),
                item.semanticRole(), item.textValue(), item.uploadId() != null, item.runOutput() != null));
        if (!errors.isEmpty()) {
            throw new DriveValidationException(errors);
        }
        if (ContentType.TEXT.wire().equals(item.contentType())) {
            return insertText(caller, item);
        }
        return item.uploadId() != null ? createFromUpload(caller, item) : createFromRunOutput(caller, item);
    }

    PageView<DriveItemRow> list(UUID spaceId, String role, String contentType, int page, int size) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (role != null && DriveRoles.contentTypeOf(role).isEmpty()) {
            errors.put("role", VALUE_INVALID);
        }
        if (contentType != null && ContentType.fromWire(contentType).isEmpty()) {
            errors.put("contentType", VALUE_INVALID);
        }
        if (!errors.isEmpty()) {
            throw new DriveValidationException(errors);
        }
        return new PageView<>(repository.page(spaceId, role, contentType, page, size), page, size,
                repository.count(spaceId, role, contentType));
    }

    DriveItemRow get(Caller caller, UUID id) {
        return repository.find(caller.spaceId(), id).orElseThrow(DriveItemService::notFound);
    }

    DriveItemRow update(Caller caller, UUID id, ItemPatch patch) {
        DriveItemRow current = get(caller, id);
        Map<String, String> errors = DriveItemRules.validateUpdate(current.contentType(),
                new UpdateInput(patch.name(), patch.semanticRole(), patch.textValue()));
        if (!errors.isEmpty()) {
            throw new DriveValidationException(errors);
        }
        if (patch.name() == null && patch.semanticRole() == null && patch.textValue() == null) {
            return current;
        }
        int matched = repository.update(caller.spaceId(), id, DriveItemRules.normalizeName(patch.name()),
                patch.semanticRole(), patch.textValue(), clock.instant());
        if (matched == 0) {
            throw notFound();
        }
        return get(caller, id);
    }

    /** Removes the item, then releases the copy of an uploaded file; a run output is left with its run. */
    void delete(Caller caller, UUID id) {
        DriveItemRow row = get(caller, id);
        if (repository.delete(caller.spaceId(), id) == 0) {
            throw notFound();
        }
        if (DriveItemRow.SOURCE_UPLOAD.equals(row.source())) {
            try {
                mediaJobs.releaseDriveFile(storedFile(row));
            } catch (RuntimeException e) {
                LOG.error("Drive file not released after its item was deleted: bucket={} key={}", row.bucket(),
                        row.objectKey(), e);
            }
        }
    }

    ItemView view(DriveItemRow row) {
        return new ItemView(row, row.isMedia() ? sign(row.bucket(), row.objectKey(), row.fileName()) : null);
    }

    MediaDownload sign(String bucket, String objectKey, String fileName) {
        return mediaJobs.presignedGet(bucket, objectKey, fileName);
    }

    private DriveItemRow insertText(Caller caller, NewItem item) {
        DriveItemRow row = new DriveItemRow(UUID.randomUUID(), caller.spaceId(), caller.userId(),
                DriveItemRules.normalizeName(item.name()), item.contentType(), item.semanticRole(), item.textValue(),
                DriveItemRow.SOURCE_TEXT, null, null, null, null, null, null, null, clock.instant(), clock.instant());
        repository.insert(row);
        return row;
    }

    private DriveItemRow createFromRunOutput(Caller caller, NewItem item) {
        RunOutput ref = item.runOutput();
        RunResultView run = runResults.completedRun(ref.runId()).orElseThrow(DriveItemService::runOutputInvalid);
        if (!run.ownerId().equals(caller.userId())) {
            throw notFound();
        }
        Output output = outputOf(run, ref.stepOrder(), ref.name())
                .filter(candidate -> mediaJobs.isDownloadable(candidate.bucket()))
                .filter(candidate -> candidate.name().length() <= FILE_NAME_MAX)
                .filter(candidate -> ResultRules.mediaKind(candidate.mime())
                        .map(ContentType::wire).filter(item.contentType()::equals).isPresent())
                .orElseThrow(DriveItemService::runOutputInvalid);
        DriveItemRow row = new DriveItemRow(UUID.randomUUID(), caller.spaceId(), caller.userId(),
                DriveItemRules.normalizeName(item.name()), item.contentType(), item.semanticRole(), null,
                DriveItemRow.SOURCE_RUN_OUTPUT, output.bucket(), output.objectKey(), output.name(), output.mime(),
                output.bytes(), run.runId(), ref.stepOrder(), clock.instant(), clock.instant());
        repository.insert(row);
        return row;
    }

    private DriveItemRow createFromUpload(Caller caller, NewItem item) {
        if (!mediaJobs.isEnabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("media.disabled"));
        }
        UploadUsage usage = repository.uploadUsage(caller.spaceId());
        if (usage.items() + 1 > properties.maxItemsPerSpace() || usage.bytes() >= properties.maxBytesPerSpace()) {
            throw quotaExceeded();
        }
        UUID itemId = UUID.randomUUID();
        StoredDriveFile file;
        try {
            file = mediaJobs.keepDriveFile(caller.userId(), item.uploadId(), item.contentType(), caller.spaceId(),
                    itemId);
        } catch (MediaStoreUnavailableException e) {
            LOG.warn("Drive file not kept: the media store is unavailable", e);
            throw storeUnavailable();
        }
        DriveItemRow row = new DriveItemRow(itemId, caller.spaceId(), caller.userId(),
                DriveItemRules.normalizeName(item.name()), item.contentType(), item.semanticRole(), null,
                DriveItemRow.SOURCE_UPLOAD, file.bucket(), file.objectKey(), file.fileName(), file.mimeType(),
                file.bytes(), null, null, clock.instant(), clock.instant());
        boolean inserted;
        try {
            inserted = repository.insertUploadWithinQuota(row, properties.maxItemsPerSpace(),
                    properties.maxBytesPerSpace());
        } catch (RuntimeException e) {
            releaseAfterFailedInsert(file);
            throw e;
        }
        if (!inserted) {
            releaseAfterFailedInsert(file);
            throw quotaExceeded();
        }
        confirmOrRemove(row, file);
        return row;
    }

    private void releaseAfterFailedInsert(StoredDriveFile file) {
        try {
            mediaJobs.releaseDriveFile(file);
        } catch (RuntimeException e) {
            LOG.warn("Drive file left pending after a failed insert: key={}", file.objectKey(), e);
        }
    }

    /** Confirms once more on a failure; a copy that stays pending would expire under a live item, so the item goes. */
    private void confirmOrRemove(DriveItemRow row, StoredDriveFile file) {
        try {
            mediaJobs.confirmDriveFile(file);
            return;
        } catch (RuntimeException first) {
            LOG.warn("Drive file not confirmed, retrying once: key={}", file.objectKey(), first);
        }
        try {
            mediaJobs.confirmDriveFile(file);
            return;
        } catch (RuntimeException second) {
            LOG.error("Drive file not confirmed after a retry, removing its item: key={}", file.objectKey(), second);
        }
        try {
            repository.delete(row.spaceId(), row.id());
        } catch (RuntimeException e) {
            LOG.error("Drive item {} still points at a pending copy: key={}", row.id(), file.objectKey(), e);
        }
        throw storeUnavailable();
    }

    private static StoredDriveFile storedFile(DriveItemRow row) {
        return new StoredDriveFile(row.bucket(), row.objectKey(), row.fileName(), row.mimeType(), row.sizeBytes(),
                row.contentType());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(new LocalizedMessage("drive.item.notFound"));
    }

    private static DriveValidationException runOutputInvalid() {
        return new DriveValidationException(Map.of(RUN_OUTPUT_FIELD, "drive.runOutput.invalid"));
    }

    private static DriveValidationException quotaExceeded() {
        return new DriveValidationException(Map.of("uploadId", "drive.quotaExceeded"));
    }

    private static LocalizedStatusException storeUnavailable() {
        return new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                new LocalizedMessage("drive.storeUnavailable"));
    }

    /** The image, video or audio output called {@code name} of a completed step of {@code run}. */
    private static Optional<Output> outputOf(RunResultView run, int stepOrder, String name) {
        return run.steps().stream()
                .filter(step -> step.stepOrder() == stepOrder && ResultRules.isCompleted(step))
                .findFirst()
                .flatMap(step -> ResultRules.mediaOutputs(step).stream()
                        .filter(output -> output.name().equals(name)).findFirst());
    }
}
