package com.flowcortex.entity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.entity.domain.EntityChanges.Change;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The next snapshot a restore writes from an older version, and the parts it cannot carry over. */
class RestorePlanTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static EntitySnapshot target() {
        return new EntitySnapshot("Nodi cũ", "nodi-cu", Map.of("voice", "warm"), "Đồ uống", List.of("Sale"), List.of(
                new EntitySnapshot.Part(A, "product_photo", "Trước", 0),
                new EntitySnapshot.Part(B, "detail", null, 1),
                new EntitySnapshot.Part(C, "back", "Sau", 2)));
    }

    @Test
    void partsWhoseAssetIsGoneAreDroppedAndReported() {
        RestorePlan plan = RestorePlan.of(target(), Set.of(A, C), 2);

        assertThat(plan.next().parts()).extracting(EntitySnapshot.Part::assetId).containsExactly(A, C);
        assertThat(plan.dropped()).containsExactly(new EntitySnapshot.Part(B, "detail", null, 1));
    }

    @Test
    void keptPartsAreRenumberedFromZeroInOrder() {
        RestorePlan plan = RestorePlan.of(target(), Set.of(A, C), 2);

        assertThat(plan.next().parts()).containsExactly(
                new EntitySnapshot.Part(A, "product_photo", "Trước", 0),
                new EntitySnapshot.Part(C, "back", "Sau", 1));
    }

    @Test
    void keptPartsFollowTheTargetsSortNotItsListOrder() {
        EntitySnapshot shuffled = target().withParts(List.of(
                new EntitySnapshot.Part(C, "back", "Sau", 2),
                new EntitySnapshot.Part(A, "product_photo", "Trước", 0)));

        RestorePlan plan = RestorePlan.of(shuffled, Set.of(A, C), 2);

        assertThat(plan.next().parts()).extracting(EntitySnapshot.Part::assetId).containsExactly(A, C);
        assertThat(plan.dropped()).isEmpty();
    }

    @Test
    void restoreCarriesNameHandleFieldsCategoryTags() {
        RestorePlan plan = RestorePlan.of(target(), Set.of(A, B, C), 2);

        assertThat(plan.next().name()).isEqualTo("Nodi cũ");
        assertThat(plan.next().handle()).isEqualTo("nodi-cu");
        assertThat(plan.next().fields()).isEqualTo(Map.of("voice", "warm"));
        assertThat(plan.next().categoryLabel()).isEqualTo("Đồ uống");
        assertThat(plan.next().tags()).containsExactly("Sale");
        assertThat(plan.dropped()).isEmpty();
    }

    @Test
    void restoredChangeNamesTheSourceVersion() {
        RestorePlan plan = RestorePlan.of(target(), Set.of(A, B, C), 2);

        assertThat(plan.changes()).hasSize(1);
        Change change = plan.changes().get(0);
        assertThat(change.code()).isEqualTo("restored");
        assertThat(change.from()).isEqualTo(2);
    }
}
