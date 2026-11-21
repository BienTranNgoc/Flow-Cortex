package com.flowcortex.drive.backfill;

import com.flowcortex.drive.domain.BackfillReconciler;
import com.flowcortex.drive.domain.BackfillReconciler.Key;
import com.flowcortex.drive.domain.BackfillReconciler.Mismatch;
import com.flowcortex.drive.domain.BackfillReconciler.Mode;
import com.flowcortex.drive.domain.BackfillReconciler.SpaceKeys;
import com.flowcortex.drive.domain.MemoryProjector;
import com.flowcortex.drive.domain.MemoryProjector.Entry;
import com.flowcortex.drive.memory.MemoryPopulation;
import com.flowcortex.drive.memory.MemoryResultRepository;
import com.flowcortex.space.api.PersonalSpaces;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResults;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings the results of runs completed before Drive into each owner's Memory and checks that Memory against
 * the runs. Reports carry ids and counts only. Runs are read in id order in batches and each run is its own
 * unit of work, so an interrupted apply resumes by running again.
 */
@Component
public class DriveBackfill {

    static final int BATCH_SIZE = 200;

    private static final Logger LOG = LoggerFactory.getLogger(DriveBackfill.class);

    private final RunResults runResults;
    private final MemoryPopulation population;
    private final PersonalSpaces personalSpaces;
    private final MemoryResultRepository memory;
    private final DriveBackfillRepository backfills;
    private final TransactionTemplate transactions;

    DriveBackfill(RunResults runResults, MemoryPopulation population, PersonalSpaces personalSpaces,
            MemoryResultRepository memory, DriveBackfillRepository backfills,
            PlatformTransactionManager transactionManager) {
        this.runResults = runResults;
        this.population = population;
        this.personalSpaces = personalSpaces;
        this.memory = memory;
        this.backfills = backfills;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** What one mode did to a space: runs and results written (apply) or that apply would write (dry-run). */
    private static final class Tally {
        private final UUID ownerId;
        private int runs;
        private int results;

        Tally(UUID ownerId) {
            this.ownerId = ownerId;
        }
    }

    /** One run as the projection sees it: where its entries belong and which keys they carry. */
    private record Projected(UUID runId, UUID spaceId, UUID ownerId, Set<String> resultKeys) {
    }

    private static final class Walk {
        private final Map<UUID, Tally> tallies = new LinkedHashMap<>();
        private final Map<UUID, UUID> spaceOwners = new LinkedHashMap<>();
        private final Map<UUID, Mismatch> mismatches = new LinkedHashMap<>();
        private int walked;
        private int skipped;
    }

    /**
     * Runs one mode over every completed run.
     *
     * @param mode one of {@code dry-run}, {@code apply}, {@code reconcile}
     * @param save with {@code reconcile}, records the outcome per space
     * @return the process exit code: 0 ok, 1 a run was skipped or the data could not be read, 2 unknown mode,
     *         3 a space differs
     */
    public int run(String mode, boolean save) {
        Optional<Mode> parsed = Mode.parse(mode);
        if (parsed.isEmpty()) {
            LOG.error("Drive backfill: unknown mode, nothing written");
            return BackfillReconciler.EXIT_UNKNOWN_MODE;
        }
        try {
            return walk(parsed.get(), save);
        } catch (DataAccessException e) {
            LOG.error("Drive backfill: data access failure error={}", e.getClass().getSimpleName());
            return BackfillReconciler.EXIT_DATA_ACCESS_FAILURE;
        }
    }

    private int walk(Mode mode, boolean save) {
        Walk walk = new Walk();
        UUID after = null;
        while (true) {
            List<UUID> batch = runResults.completedRunIds(after, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            if (mode == Mode.APPLY) {
                batch.forEach(runId -> applyRun(runId, walk));
            } else {
                inspectBatch(batch, mode, walk);
            }
            walk.walked += batch.size();
            after = batch.get(batch.size() - 1);
        }
        if (mode == Mode.RECONCILE && save) {
            walk.spaceOwners.forEach((spaceId, ownerId) ->
                    backfills.saveReconcile(spaceId, ownerId, !walk.mismatches.containsKey(spaceId)));
        }
        return report(mode, walk);
    }

    private void applyRun(UUID runId, Walk walk) {
        try {
            transactions.executeWithoutResult(status -> {
                Optional<MemoryPopulation.Outcome> outcome = population.populate(runId);
                if (outcome.isEmpty() || outcome.get().inserted() == 0) {
                    return;
                }
                MemoryPopulation.Outcome written = outcome.get();
                backfills.recordApply(written.spaceId(), written.ownerId(), 1, written.inserted());
                Tally tally = walk.tallies.computeIfAbsent(written.spaceId(), id -> new Tally(written.ownerId()));
                tally.runs++;
                tally.results += written.inserted();
            });
        } catch (RuntimeException e) {
            skip(runId, e, walk);
        }
    }

    /** Projects the batch and compares it with Memory inside a transaction that is never committed. */
    private void inspectBatch(List<UUID> batch, Mode mode, Walk walk) {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            List<Projected> projected = new ArrayList<>();
            for (UUID runId : batch) {
                try {
                    project(runId).ifPresent(projected::add);
                } catch (RuntimeException e) {
                    skip(runId, e, walk);
                }
            }
            Map<UUID, SpaceKeys> stored = memory.keysBySpace(projected.stream().map(Projected::runId).toList());
            if (mode == Mode.DRY_RUN) {
                countWhatApplyWouldWrite(projected, stored, walk);
            } else {
                reconcileBatch(projected, stored, walk);
            }
        });
    }

    private Optional<Projected> project(UUID runId) {
        Optional<RunResultView> run = runResults.completedRun(runId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        Set<String> keys = new HashSet<>();
        for (Entry entry : MemoryProjector.project(run.get())) {
            keys.add(entry.resultKey());
        }
        UUID ownerId = run.get().ownerId();
        return Optional.of(new Projected(runId, personalSpaces.personalSpaceOf(ownerId), ownerId, keys));
    }

    private void countWhatApplyWouldWrite(List<Projected> projected, Map<UUID, SpaceKeys> stored, Walk walk) {
        for (Projected run : projected) {
            Set<Key> held = stored.containsKey(run.spaceId()) ? stored.get(run.spaceId()).keys() : Set.of();
            long missing = run.resultKeys().stream().filter(key -> !held.contains(new Key(run.runId(), key))).count();
            if (missing > 0) {
                Tally tally = walk.tallies.computeIfAbsent(run.spaceId(), id -> new Tally(run.ownerId()));
                tally.runs++;
                tally.results += (int) missing;
            }
        }
    }

    private void reconcileBatch(List<Projected> projected, Map<UUID, SpaceKeys> stored, Walk walk) {
        Map<UUID, SpaceKeys> wanted = new HashMap<>();
        for (Projected run : projected) {
            walk.spaceOwners.putIfAbsent(run.spaceId(), run.ownerId());
            SpaceKeys keys = wanted.computeIfAbsent(run.spaceId(),
                    id -> new SpaceKeys(run.ownerId(), new HashSet<>()));
            run.resultKeys().forEach(key -> keys.keys().add(new Key(run.runId(), key)));
        }
        for (Mismatch mismatch : BackfillReconciler.compare(wanted, stored)) {
            walk.spaceOwners.putIfAbsent(mismatch.spaceId(), mismatch.ownerId());
            walk.mismatches.merge(mismatch.spaceId(), mismatch, DriveBackfill::merge);
        }
    }

    private static Mismatch merge(Mismatch a, Mismatch b) {
        Set<UUID> runIds = new TreeSet<>(a.runIds());
        runIds.addAll(b.runIds());
        return new Mismatch(a.spaceId(), a.ownerId(), a.projected() + b.projected(), a.stored() + b.stored(),
                a.missing() + b.missing(), a.extra() + b.extra(), List.copyOf(runIds));
    }

    private void skip(UUID runId, RuntimeException error, Walk walk) {
        walk.skipped++;
        LOG.error(BackfillReconciler.skippedRunLine(runId, error.getClass()));
    }

    private int report(Mode mode, Walk walk) {
        String label = mode == Mode.DRY_RUN ? "dry-run would write" : "apply wrote";
        walk.tallies.forEach((spaceId, tally) -> LOG.info("Drive backfill: {} ownerId={} spaceId={} runs={} results={}",
                label, tally.ownerId, spaceId, tally.runs, tally.results));
        walk.mismatches.values().forEach(mismatch -> LOG.warn(BackfillReconciler.reportLine(mismatch)));
        LOG.info("Drive backfill: mode={} runsWalked={} spaces={} skipped={} mismatchedSpaces={}",
                mode.name().toLowerCase().replace('_', '-'), walk.walked, walk.tallies.size(), walk.skipped,
                walk.mismatches.size());
        if (walk.skipped > 0) {
            return BackfillReconciler.EXIT_DATA_ACCESS_FAILURE;
        }
        return BackfillReconciler.exitCode(List.copyOf(walk.mismatches.values()));
    }
}
