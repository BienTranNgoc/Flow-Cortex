package com.flowcortex.drive.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Compares projected against stored Memory keys per space and words the command's exit codes and report lines. */
public final class BackfillReconciler {

    public static final int EXIT_OK = 0;
    public static final int EXIT_DATA_ACCESS_FAILURE = 1;
    public static final int EXIT_UNKNOWN_MODE = 2;
    public static final int EXIT_MISMATCH = 3;

    /** The command modes, spelled as the operator passes them. */
    public enum Mode {
        DRY_RUN("dry-run"), APPLY("apply"), RECONCILE("reconcile");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        public static Optional<Mode> parse(String value) {
            for (Mode mode : values()) {
                if (mode.wire.equals(value)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }
    }

    /** One Memory entry's identity: the run it belongs to and its result key. */
    public record Key(UUID runId, String resultKey) {
    }

    /** The keys one side holds for a space. */
    public record SpaceKeys(UUID ownerId, Set<Key> keys) {
    }

    /** A space whose stored keys differ from the projected ones. */
    public record Mismatch(UUID spaceId, UUID ownerId, int projected, int stored, int missing, int extra,
            List<UUID> runIds) {
    }

    private BackfillReconciler() {
    }

    /** Spaces whose key sets differ, in space id order; a space present on one side only counts as differing. */
    public static List<Mismatch> compare(Map<UUID, SpaceKeys> projected, Map<UUID, SpaceKeys> stored) {
        Set<UUID> spaces = new TreeSet<>(projected.keySet());
        spaces.addAll(stored.keySet());
        List<Mismatch> mismatches = new ArrayList<>();
        for (UUID spaceId : spaces) {
            SpaceKeys want = projected.get(spaceId);
            SpaceKeys have = stored.get(spaceId);
            Set<Key> wanted = want == null ? Set.of() : want.keys();
            Set<Key> held = have == null ? Set.of() : have.keys();
            Set<Key> missing = new HashSet<>(wanted);
            missing.removeAll(held);
            Set<Key> extra = new HashSet<>(held);
            extra.removeAll(wanted);
            if (missing.isEmpty() && extra.isEmpty()) {
                continue;
            }
            Set<UUID> runIds = new TreeSet<>();
            missing.forEach(key -> runIds.add(key.runId()));
            extra.forEach(key -> runIds.add(key.runId()));
            UUID ownerId = want != null ? want.ownerId() : have.ownerId();
            mismatches.add(new Mismatch(spaceId, ownerId, wanted.size(), held.size(), missing.size(), extra.size(),
                    List.copyOf(runIds)));
        }
        return mismatches;
    }

    public static int exitCode(List<Mismatch> mismatches) {
        return mismatches.isEmpty() ? EXIT_OK : EXIT_MISMATCH;
    }

    /** Ids and counts only: no URL, object key, file name or address ever reaches a report. */
    public static String reportLine(Mismatch mismatch) {
        return "reconcile mismatch ownerId=" + mismatch.ownerId() + " spaceId=" + mismatch.spaceId()
                + " projected=" + mismatch.projected() + " stored=" + mismatch.stored()
                + " missing=" + mismatch.missing() + " extra=" + mismatch.extra()
                + " runIds=" + mismatch.runIds();
    }

    /** A run left out of the walk, named by id and exception class only. */
    public static String skippedRunLine(UUID runId, Class<? extends Throwable> error) {
        return "skipped run runId=" + runId + " error=" + error.getSimpleName();
    }
}
