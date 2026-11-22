package com.flowcortex.entity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.entity.domain.EntityChanges.Change;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What differs between two snapshots, as the change codes a version records. */
class EntityChangesTest {

    private static final UUID LOGO = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PHOTO = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static EntitySnapshot base() {
        return new EntitySnapshot("Nodi", "nodi", Map.of("voice", "warm"), "Đồ uống", List.of("Mùa Hè"),
                List.of(new EntitySnapshot.Part(LOGO, "logo", "Chính", 0), new EntitySnapshot.Part(PHOTO, "logo", null, 1)));
    }

    private static List<String> codes(List<Change> changes) {
        return changes.stream().map(Change::code).toList();
    }

    @Test
    void identicalSnapshotsHaveNoChange() {
        assertThat(EntityChanges.diff(base(), base())).isEmpty();
    }

    @Test
    void renameIsRenamed() {
        EntitySnapshot after = base().withName("Nodi Vietnam");

        assertThat(codes(EntityChanges.diff(base(), after))).containsExactly("renamed");
    }

    @Test
    void handleIsHandleChanged() {
        EntitySnapshot after = base().withHandle("nodi-vn");

        assertThat(codes(EntityChanges.diff(base(), after))).containsExactly("handleChanged");
    }

    @Test
    void fieldsKeyOrderDoesNotCount() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", List.of("x", "y"));
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", List.of("x", "y"));
        ba.put("a", "1");

        assertThat(EntityChanges.diff(base().withFields(ab), base().withFields(ba))).isEmpty();
        assertThat(codes(EntityChanges.diff(base().withFields(ab), base().withFields(Map.of("a", "2")))))
                .containsExactly("fieldsChanged");
        assertThat(codes(EntityChanges.diff(base().withFields(Map.of("l", List.of("x", "y"))),
                base().withFields(Map.of("l", List.of("y", "x")))))).containsExactly("fieldsChanged");
    }

    @Test
    void partsReorderIsPartsChanged() {
        EntitySnapshot after = base().withParts(List.of(
                new EntitySnapshot.Part(PHOTO, "logo", null, 0), new EntitySnapshot.Part(LOGO, "logo", "Chính", 1)));

        assertThat(codes(EntityChanges.diff(base(), after))).containsExactly("partsChanged");
    }

    @Test
    void partsRelabelOrRemovalIsPartsChanged() {
        EntitySnapshot relabelled = base().withParts(List.of(
                new EntitySnapshot.Part(LOGO, "logo", "Phụ", 0), new EntitySnapshot.Part(PHOTO, "logo", null, 1)));

        assertThat(codes(EntityChanges.diff(base(), relabelled))).containsExactly("partsChanged");
        assertThat(codes(EntityChanges.diff(base(), base().withParts(List.of())))).containsExactly("partsChanged");
    }

    @Test
    void tagsChangedListsAddedAndRemovedLabels() {
        EntitySnapshot after = base().withTags(List.of("Sale", "Tết"));

        List<Change> changes = EntityChanges.diff(base(), after);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).code()).isEqualTo("tagsChanged");
        assertThat(changes.get(0).added()).containsExactly("Sale", "Tết");
        assertThat(changes.get(0).removed()).containsExactly("Mùa Hè");
    }

    @Test
    void tagOrderAndSpellingOfTheSameKeyDoNotCount() {
        EntitySnapshot before = base().withTags(List.of("Mùa Hè", "Sale"));
        EntitySnapshot after = base().withTags(List.of("sale", "mua he"));

        assertThat(EntityChanges.diff(before, after)).isEmpty();
    }

    @Test
    void categoryClearedIsCategoryChanged() {
        assertThat(codes(EntityChanges.diff(base(), base().withCategoryLabel(null)))).containsExactly("categoryChanged");
        assertThat(codes(EntityChanges.diff(base().withCategoryLabel(null), base()))).containsExactly("categoryChanged");
        assertThat(codes(EntityChanges.diff(base(), base().withCategoryLabel("Đồ ăn")))).containsExactly("categoryChanged");
    }

    @Test
    void codesComeInFixedOrder() {
        EntitySnapshot after = new EntitySnapshot("Khác", "khac", Map.of("voice", "cold"), null, List.of("Sale"), List.of());

        assertThat(codes(EntityChanges.diff(base(), after))).containsExactly(
                "renamed", "handleChanged", "fieldsChanged", "partsChanged", "tagsChanged", "categoryChanged");
    }

    @Test
    void createdAndRestoredCarryTheirOwnShape() {
        assertThat(Change.created().code()).isEqualTo("created");
        Change restored = Change.restored(2);
        assertThat(restored.code()).isEqualTo("restored");
        assertThat(restored.from()).isEqualTo(2);
    }
}
