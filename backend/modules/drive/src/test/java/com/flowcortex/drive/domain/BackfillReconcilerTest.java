package com.flowcortex.drive.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.drive.domain.BackfillReconciler.Key;
import com.flowcortex.drive.domain.BackfillReconciler.Mismatch;
import com.flowcortex.drive.domain.BackfillReconciler.Mode;
import com.flowcortex.drive.domain.BackfillReconciler.SpaceKeys;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Per-space comparison of the entries the runs project against the entries stored. */
class BackfillReconcilerTest {

    private final UUID spaceId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    private Key key(String resultKey) {
        return new Key(runId, resultKey);
    }

    private SpaceKeys keys(String... resultKeys) {
        return new SpaceKeys(ownerId, Set.of(java.util.Arrays.stream(resultKeys).map(this::key).toArray(Key[]::new)));
    }

    @Test
    void equalKeySetsAreOk() {
        Map<UUID, SpaceKeys> both = Map.of(spaceId, keys("a", "b"));

        assertThat(BackfillReconciler.compare(both, Map.of(spaceId, keys("b", "a")))).isEmpty();
        assertThat(BackfillReconciler.compare(Map.of(), Map.of())).isEmpty();
    }

    @Test
    void aMissingOrExtraKeyIsAMismatchWithBothCounts() {
        List<Mismatch> missing = BackfillReconciler.compare(Map.of(spaceId, keys("a", "b", "c", "d")),
                Map.of(spaceId, keys("a", "b", "c")));

        assertThat(missing).hasSize(1);
        Mismatch one = missing.getFirst();
        assertThat(one.spaceId()).isEqualTo(spaceId);
        assertThat(one.ownerId()).isEqualTo(ownerId);
        assertThat(one.projected()).isEqualTo(4);
        assertThat(one.stored()).isEqualTo(3);
        assertThat(one.missing()).isEqualTo(1);
        assertThat(one.extra()).isZero();
        assertThat(one.runIds()).containsExactly(runId);

        List<Mismatch> extra = BackfillReconciler.compare(Map.of(spaceId, keys("a")),
                Map.of(spaceId, keys("a", "z")));
        assertThat(extra.getFirst().extra()).isEqualTo(1);
        assertThat(extra.getFirst().missing()).isZero();

        UUID storedOnly = UUID.randomUUID();
        List<Mismatch> unprojected = BackfillReconciler.compare(Map.of(), Map.of(storedOnly, keys("a")));
        assertThat(unprojected).extracting(Mismatch::spaceId).containsExactly(storedOnly);
        assertThat(unprojected.getFirst().projected()).isZero();

        List<Mismatch> unstored = BackfillReconciler.compare(Map.of(storedOnly, keys("a")), Map.of());
        assertThat(unstored.getFirst().stored()).isZero();
        assertThat(unstored.getFirst().missing()).isEqualTo(1);
    }

    @Test
    void exitCodeIs0WhenEverySpaceMatches3OnAnyMismatch1OnDataAccessFailure2OnUnknownMode() {
        Mismatch mismatch = BackfillReconciler.compare(Map.of(spaceId, keys("a")), Map.of()).getFirst();

        assertThat(BackfillReconciler.exitCode(List.of())).isEqualTo(0);
        assertThat(BackfillReconciler.exitCode(List.of(mismatch))).isEqualTo(3);
        assertThat(BackfillReconciler.EXIT_DATA_ACCESS_FAILURE).isEqualTo(1);
        assertThat(BackfillReconciler.EXIT_UNKNOWN_MODE).isEqualTo(2);
        assertThat(Mode.parse("dry-run")).contains(Mode.DRY_RUN);
        assertThat(Mode.parse("apply")).contains(Mode.APPLY);
        assertThat(Mode.parse("reconcile")).contains(Mode.RECONCILE);
        assertThat(Mode.parse("migrate")).isEmpty();
        assertThat(Mode.parse("APPLY")).isEmpty();
        assertThat(Mode.parse(null)).isEmpty();
    }

    @Test
    void reportLinesHoldOnlyIdsAndCounts() {
        Mismatch mismatch = BackfillReconciler.compare(
                Map.of(spaceId, keys("secret/final video.mp4", "b")), Map.of(spaceId, keys("b"))).getFirst();

        String line = BackfillReconciler.reportLine(mismatch);

        assertThat(line).contains(ownerId.toString(), spaceId.toString(), runId.toString())
                .contains("projected=2", "stored=1", "missing=1", "extra=0");
        assertThat(line).doesNotContain("secret", ".mp4", "http", "@", "/");
        assertThat(BackfillReconciler.skippedRunLine(runId, IllegalStateException.class))
                .isEqualTo("skipped run runId=" + runId + " error=IllegalStateException");
    }
}
