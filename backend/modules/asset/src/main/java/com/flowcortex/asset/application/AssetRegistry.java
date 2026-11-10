package com.flowcortex.asset.application;

import com.flowcortex.asset.domain.AssetNaming;
import com.flowcortex.asset.domain.FinalOutputSelector;
import com.flowcortex.asset.domain.NewSpaceAsset;
import com.flowcortex.asset.domain.OutputCandidate;
import com.flowcortex.asset.domain.RegistrationRow;
import com.flowcortex.asset.domain.RegistrationStatus;
import com.flowcortex.asset.domain.SourceType;
import com.flowcortex.asset.infrastructure.AssetRegistrationStore;
import com.flowcortex.asset.infrastructure.SpaceAssetStore;
import com.flowcortex.media.api.BoundRunInput;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.api.RegistrableMedia;
import com.flowcortex.media.api.StoredMediaFile;
import com.flowcortex.space.api.PersonalSpaces;
import com.flowcortex.workflow.api.RunRecord;
import com.flowcortex.workflow.api.RunRecordStep;
import com.flowcortex.workflow.api.RunRecords;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns a completed run's final outputs and a started run's bound inputs into library assets in the
 * owner's personal space. Work is recorded in a durable queue row first; every failure after that
 * is caught and left on the row for the next attempt, so no caller ever sees one.
 */
@Service
public class AssetRegistry {

    static final String RUN_MISSING = "run_missing";
    static final String RUN_NOT_COMPLETED = "run_not_completed";
    static final String FOREIGN_OWNER = "foreign_owner";
    static final String OBJECT_MISSING = "object_missing";
    static final String UNSUPPORTED_KIND = "unsupported_kind";
    static final String STORE_UNAVAILABLE = "store_unavailable";
    static final String INSERT_FAILED = "insert_failed";
    static final String UNEXPECTED = "unexpected";
    static final String EXISTS = "exists";

    private static final Logger LOG = LoggerFactory.getLogger(AssetRegistry.class);
    private static final String SOURCE_RUN = "run";
    private static final String SOURCE_UPLOAD = "upload";
    private static final Set<String> LIBRARY_KINDS = Set.of("video", "image", "audio");

    private final RunRecords runs;
    private final RegistrableMedia media;
    private final PersonalSpaces spaces;
    private final AssetRegistrationStore queue;
    private final SpaceAssetStore assets;
    private final AssetRegistrationPool pool;

    AssetRegistry(RunRecords runs, RegistrableMedia media, PersonalSpaces spaces, AssetRegistrationStore queue,
            SpaceAssetStore assets, AssetRegistrationPool pool) {
        this.runs = runs;
        this.media = media;
        this.spaces = spaces;
        this.queue = queue;
        this.assets = assets;
        this.pool = pool;
    }

    /**
     * Records the source in the queue (this alone may throw) and hands its processing to the
     * registration pool. Inside a transaction the hand-off waits for the commit, because the pool
     * claims the row on its own connection and would otherwise find nothing. A rejected hand-off
     * leaves the row pending and due for the next sweep.
     */
    public void announce(SourceType sourceType, UUID runId, UUID ownerId) {
        queue.upsertPending(sourceType, runId, ownerId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    handOff(sourceType, runId);
                }
            });
        } else {
            handOff(sourceType, runId);
        }
    }

    private void handOff(SourceType sourceType, UUID runId) {
        try {
            pool.execute(() -> claimAndProcess(sourceType, runId));
        } catch (RejectedExecutionException e) {
            LOG.warn("asset.registry outcome=deferred source={} runId={} registered=0 skipped=0 code=pool_full",
                    sourceType.column(), runId);
        }
    }

    void claimAndProcess(SourceType sourceType, UUID runId) {
        try {
            Optional<RegistrationRow> claimed = queue.claim(sourceType, runId);
            if (claimed.isEmpty()) {
                LOG.info("asset.registry outcome=not_claimed source={} runId={} registered=0 skipped=0 code=-",
                        sourceType.column(), runId);
                return;
            }
            process(claimed.get());
        } catch (RuntimeException e) {
            LOG.error("asset.registry outcome=claim_failed source={} runId={}", sourceType.column(), runId, e);
        }
    }

    /** Processes a claimed row and ends it as done, skipped or retried; never throws. */
    public void process(RegistrationRow row) {
        Tally tally = new Tally();
        try {
            if (row.sourceType() == SourceType.RUN_OUTPUTS) {
                registerOutputs(row, tally);
            } else {
                registerInputs(row, tally);
            }
        } catch (MediaStoreUnavailableException e) {
            LOG.warn("asset.registry store unavailable runId={}", row.sourceId(), e);
            tally.transientFailure(STORE_UNAVAILABLE);
        } catch (DataAccessException e) {
            LOG.warn("asset.registry data access failed runId={}", row.sourceId(), e);
            tally.transientFailure(INSERT_FAILED);
        } catch (RuntimeException e) {
            LOG.error("asset.registry unexpected failure runId={}", row.sourceId(), e);
            tally.transientFailure(UNEXPECTED);
        }
        finishRow(row, tally);
    }

    private void finishRow(RegistrationRow row, Tally tally) {
        String outcome;
        String code;
        try {
            if (tally.transientCode != null) {
                outcome = "retry";
                code = tally.transientCode;
                queue.retry(row, code);
            } else if (tally.wholeSourceSkipCode != null) {
                outcome = "skipped";
                code = tally.wholeSourceSkipCode;
                queue.finish(row, RegistrationStatus.SKIPPED);
            } else {
                outcome = "done";
                code = tally.lastSkipCode == null ? "-" : tally.lastSkipCode;
                queue.finish(row, RegistrationStatus.DONE);
            }
        } catch (RuntimeException e) {
            // The row keeps its lease and is taken again once it lapses.
            LOG.error("asset.registry outcome=finish_failed source={} runId={}",
                    row.sourceType().column(), row.sourceId(), e);
            return;
        }
        LOG.info("asset.registry outcome={} source={} runId={} registered={} skipped={} code={}",
                outcome, row.sourceType().column(), row.sourceId(), tally.registered, tally.skipped, code);
    }

    private void registerOutputs(RegistrationRow row, Tally tally) {
        Optional<RunRecord> found = runs.find(row.sourceId());
        if (found.isEmpty()) {
            tally.skipSource(RUN_MISSING);
            return;
        }
        RunRecord run = found.get();
        if (!"completed".equals(run.status())) {
            tally.skipSource(RUN_NOT_COMPLETED);
            return;
        }
        UUID spaceId = spaces.personalSpaceOf(run.ownerId());

        List<RunRecordStep> descending = run.steps().stream()
                .sorted(Comparator.comparingInt(RunRecordStep::stepOrder).reversed())
                .toList();
        List<FinalOutputSelector.StepOutputs> stepOutputs = new ArrayList<>();
        for (RunRecordStep step : descending) {
            List<OutputCandidate> outputs = media.stepOutputs(step.runStepId(), step.attempt()).stream()
                    .map(file -> candidateOf(step.runStepId(), file))
                    .toList();
            stepOutputs.add(new FinalOutputSelector.StepOutputs(step.runStepId(), outputs));
        }
        FinalOutputSelector.Selection selection = FinalOutputSelector.select(stepOutputs);
        selection.skipped().forEach(skipped -> tally.skipOne(skipped.reason()));
        if (selection.candidates().isEmpty()) {
            return;
        }

        String base = AssetNaming.runTitleBase(run.titleInput(), run.workflowName(), run.runId());
        OptionalInt written = assets.runTitleVersion(run.runId());
        int version = written.isPresent()
                ? written.getAsInt()
                : assets.maxRunTitleVersion(spaceId, base, run.runId()) + 1;
        int total = selection.candidates().size();
        int index = 0;
        for (OutputCandidate candidate : selection.candidates()) {
            index++;
            if (!run.ownerId().equals(candidate.ownerId())) {
                tally.skipOne(FOREIGN_OWNER);
                continue;
            }
            String title = AssetNaming.runTitle(base, version, index, total);
            insert(new NewSpaceAsset(UUID.randomUUID(), spaceId, run.ownerId(), candidate.mediaAssetId(),
                    SOURCE_RUN, candidate.kind(), title, base, version, candidate.bucket(), candidate.objectKey(),
                    candidate.mimeType(), candidate.bytes(), run.runId(), candidate.stepId(), run.workflowId(),
                    run.workflowName()), tally);
        }
    }

    private void registerInputs(RegistrationRow row, Tally tally) {
        Optional<RunRecord> found = runs.find(row.sourceId());
        if (found.isEmpty()) {
            tally.skipSource(RUN_MISSING);
            return;
        }
        RunRecord run = found.get();
        List<BoundRunInput> inputs = media.boundInputs(run.runId());
        if (inputs.isEmpty()) {
            return;
        }
        UUID spaceId = spaces.personalSpaceOf(run.ownerId());
        for (BoundRunInput input : inputs) {
            if (!run.ownerId().equals(input.ownerId())) {
                tally.skipOne(FOREIGN_OWNER);
                continue;
            }
            if (!LIBRARY_KINDS.contains(input.kind())) {
                tally.skipOne(UNSUPPORTED_KIND);
                continue;
            }
            if (assets.existsByMediaAssetId(input.mediaAssetId())) {
                tally.skipOne(EXISTS);
                continue;
            }
            OptionalLong copyBytes;
            try {
                copyBytes = media.keepRunInput(input.bucket(), input.objectKey());
            } catch (MediaStoreUnavailableException e) {
                LOG.warn("asset.registry keep failed runId={} mediaAssetId={}", run.runId(), input.mediaAssetId(), e);
                tally.transientFailure(STORE_UNAVAILABLE);
                continue;
            }
            if (copyBytes.isEmpty()) {
                tally.skipOne(OBJECT_MISSING);
                continue;
            }
            String title = AssetNaming.uploadTitle(run.inputFileName(), inputs.size(), run.workflowName(),
                    input.bindingKey());
            insert(new NewSpaceAsset(UUID.randomUUID(), spaceId, run.ownerId(), input.mediaAssetId(),
                    SOURCE_UPLOAD, input.kind(), title, title, 1, input.bucket(), input.objectKey(),
                    input.mimeType(), copyBytes.getAsLong(), run.runId(), null, run.workflowId(), run.workflowName()),
                    tally);
        }
    }

    private void insert(NewSpaceAsset asset, Tally tally) {
        try {
            if (assets.insertIfAbsent(asset)) {
                tally.registered++;
            } else {
                tally.skipOne(EXISTS);
            }
        } catch (DataAccessException e) {
            LOG.warn("asset.registry insert failed runId={} mediaAssetId={}", asset.runId(), asset.mediaAssetId(), e);
            tally.transientFailure(INSERT_FAILED);
        }
    }

    private static OutputCandidate candidateOf(UUID stepId, StoredMediaFile file) {
        return new OutputCandidate(file.mediaAssetId(), file.ownerId(), stepId, file.kind(), file.bucket(),
                file.objectKey(), file.mimeType(), file.bytes(), file.inMediaBucket());
    }

    /** What one processing pass did: counts for the log line and the verdict the row ends with. */
    private static final class Tally {
        int registered;
        int skipped;
        String lastSkipCode;
        String wholeSourceSkipCode;
        String transientCode;

        void skipOne(String code) {
            skipped++;
            lastSkipCode = code;
        }

        void skipSource(String code) {
            wholeSourceSkipCode = code;
        }

        void transientFailure(String code) {
            if (transientCode == null) {
                transientCode = code;
            }
        }
    }
}
